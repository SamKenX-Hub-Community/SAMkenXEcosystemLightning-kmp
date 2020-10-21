package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.Features
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.fee.FeeEstimator
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.sphinx.FailurePacket
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.ByteVector32KSerializer
import fr.acinq.eclair.io.ByteVector64KSerializer
import fr.acinq.eclair.io.ByteVectorKSerializer
import fr.acinq.eclair.io.PublicKeyKSerializer
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.eclair.transactions.Transactions.commitTxFee
import fr.acinq.eclair.transactions.Transactions.commitTxFeeMsat
import fr.acinq.eclair.transactions.Transactions.htlcOutputFee
import fr.acinq.eclair.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.eclair.transactions.Transactions.offeredHtlcTrimThreshold
import fr.acinq.eclair.transactions.Transactions.receivedHtlcTrimThreshold
import fr.acinq.eclair.transactions.incomings
import fr.acinq.eclair.transactions.outgoings
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlinx.serialization.Serializable
import org.kodein.log.Logger
import kotlin.experimental.and

// @formatter:off
@Serializable
data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    val all: List<UpdateMessage> get() = proposed + signed + acked
}

@Serializable
data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>)
data class Changes(val ourChanges: LocalChanges, val theirChanges: RemoteChanges)
@Serializable
data class HtlcTxAndSigs(val txinfo: TransactionWithInputInfo, @Serializable(with = ByteVector64KSerializer::class) val localSig: ByteVector64, @Serializable(with = ByteVector64KSerializer::class) val remoteSig: ByteVector64)
@Serializable
data class PublishableTxs(val commitTx: CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>)
@Serializable
data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs)
@Serializable
data class RemoteCommit(val index: Long, val spec: CommitmentSpec, @Serializable(with = ByteVector32KSerializer::class) val txid: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remotePerCommitmentPoint: PublicKey)
@Serializable
data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false)
// @formatter:on

/**
 * about remoteNextCommitInfo:
 * we either:
 * - have built and signed their next commit tx with their next revocation hash which can now be discarded
 * - have their next per-commitment point
 * So, when we've signed and sent a commit message and are waiting for their revocation message,
 * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
 */
@Serializable
data class Commitments(
    val channelVersion: ChannelVersion,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val channelFlags: Byte,
    val localCommit: LocalCommit,
    val remoteCommit: RemoteCommit,
    val localChanges: LocalChanges,
    val remoteChanges: RemoteChanges,
    val localNextHtlcId: Long,
    val remoteNextHtlcId: Long,
    val payments: Map<Long, UUID>, // for outgoing htlcs, maps to paymenId
    val remoteNextCommitInfo: Either<WaitingForRevocation, @Serializable(with = PublicKeyKSerializer::class) PublicKey>,
    val commitInput: Transactions.InputInfo,
    val remotePerCommitmentSecrets: ShaChain,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    @Serializable(with = ByteVectorKSerializer::class) val remoteChannelData: ByteVector = ByteVector.empty
) {

    fun updateFeatures(localInit: Init, remoteInit: Init) = this.copy(
        localParams = localParams.copy(features = Features(localInit.features)),
        remoteParams = remoteParams.copy(features = Features(remoteInit.features))
    )

    fun hasNoPendingHtlcs(): Boolean = localCommit.spec.htlcs.isEmpty() && remoteCommit.spec.htlcs.isEmpty() && remoteNextCommitInfo.isRight

    /**
     * @return true if channel was never open, or got closed immediately, had never any htlcs and local never had a positive balance
     */
    fun nothingAtStake(): Boolean = localCommit.index == 0L &&
            localCommit.spec.toLocal == 0.msat &&
            remoteCommit.index == 0L &&
            remoteCommit.spec.toRemote == 0.msat &&
            remoteNextCommitInfo.isRight


    fun timedOutOutgoingHtlcs(blockheight: Long): Set<UpdateAddHtlc> {
        fun expired(add: UpdateAddHtlc) = blockheight >= add.cltvExpiry.toLong()

        val thisCommitAdds = localCommit.spec.htlcs.outgoings().filter(::expired).toSet() +
                remoteCommit.spec.htlcs.incomings().filter(::expired).toSet()

        return when (remoteNextCommitInfo) {
            is Either.Left -> thisCommitAdds + remoteNextCommitInfo.value.nextRemoteCommit.spec.htlcs.incomings().filter(::expired).toSet()
            is Either.Right -> thisCommitAdds
        }
    }

    val isZeroReserve: Boolean get() = channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)

    /**
     * Incoming HTLCs that are close to timing out are potentially dangerous. If we released the pre-image for those
     * HTLCs, we need to get a remote signed updated commitment that removes this HTLC.
     * Otherwise when we get close to the timeout, we risk an on-chain race condition between their HTLC timeout
     * and our HTLC success in case of a force-close.
     */
    fun almostTimedOutIncomingHtlcs(blockheight: Long, fulfillSafety: CltvExpiryDelta): Set<UpdateAddHtlc> =
        localCommit.spec.htlcs
            .incomings()
            .filter { blockheight >= (it.cltvExpiry - fulfillSafety).toLong() }
            .toSet()

    fun addLocalProposal(proposal: UpdateMessage): Commitments =
        copy(localChanges = localChanges.copy(proposed = localChanges.proposed + proposal))

    fun addRemoteProposal(proposal: UpdateMessage): Commitments =
        copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed + proposal))

    val announceChannel: Boolean get() = (channelFlags and 0x01).toInt() != 0

    // NB: when computing availableBalanceForSend and availableBalanceForReceive, the funder keeps an extra buffer on top
    // of its usual channel reserve to avoid getting channels stuck in case the on-chain feerate increases (see
    // https://github.com/lightningnetwork/lightning-rfc/issues/728 for details).
    //
    // This extra buffer (which we call "funder fee buffer") is calculated as follows:
    //  1) Simulate a x2 feerate increase and compute the corresponding commit tx fee (note that it may trim some HTLCs)
    //  2) Add the cost of adding a new untrimmed HTLC at that increased feerate. This ensures that we'll be able to
    //     actually use the channel to add new HTLCs if the feerate doubles.
    //
    // If for example the current feerate is 1000 sat/kw, the dust limit 546 sat, and we have 3 pending outgoing HTLCs for
    // respectively 1250 sat, 2000 sat and 2500 sat.
    // commit tx fee = commitWeight * feerate + 3 * htlcOutputWeight * feerate = 724 * 1000 + 3 * 172 * 1000 = 1240 sat
    // To calculate the funder fee buffer, we first double the feerate and calculate the corresponding commit tx fee.
    // By doubling the feerate, the first HTLC becomes trimmed so the result is: 724 * 2000 + 2 * 172 * 2000 = 2136 sat
    // We then add the additional fee for a potential new untrimmed HTLC: 172 * 2000 = 344 sat
    // The funder fee buffer is 2136 + 344 = 2480 sat
    //
    // If there are many pending HTLCs that are only slightly above the trim threshold, the funder fee buffer may be
    // smaller than the current commit tx fee because those HTLCs will be trimmed and the commit tx weight will decrease.
    // For example if we have 10 outgoing HTLCs of 1250 sat:
    //  - commit tx fee = 724 * 1000 + 10 * 172 * 1000 = 2444 sat
    //  - commit tx fee at twice the feerate = 724 * 2000 = 1448 sat (all HTLCs have been trimmed)
    //  - cost of an additional untrimmed HTLC = 172 * 2000 = 344 sat
    //  - funder fee buffer = 1448 + 344 = 1792 sat
    // In that case the current commit tx fee is higher than the funder fee buffer and will dominate the balance restrictions.

    fun availableBalanceForSend(): MilliSatoshi {
        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
        val remoteCommit1 = when (remoteNextCommitInfo) {
            is Either.Left -> remoteNextCommitInfo.value.nextRemoteCommit
            is Either.Right -> remoteCommit
        }
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, remoteChanges.acked, localChanges.proposed)
        val balanceNoFees = (reduced.toRemote - remoteParams.channelReserve.toMilliSatoshi()).coerceAtLeast(0.msat)
        return if (localParams.isFunder) {
            // The funder always pays the on-chain fees, so we must subtract that from the amount we can send.
            val commitFees = commitTxFeeMsat(remoteParams.dustLimit, reduced)
            // the funder needs to keep a "funder fee buffer" (see explanation above)
            val funderFeeBuffer = commitTxFeeMsat(remoteParams.dustLimit, reduced.copy(feeratePerKw = 2 * reduced.feeratePerKw)) + htlcOutputFee(2 * reduced.feeratePerKw)
            val amountToReserve = commitFees.coerceAtLeast(funderFeeBuffer)
            if (balanceNoFees - amountToReserve < offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - amountToReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                val commitFees1 = commitFees + htlcOutputFee(reduced.feeratePerKw)
                // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
                val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(2 * reduced.feeratePerKw)
                val amountToReserve1 = commitFees1.coerceAtLeast(funderFeeBuffer1)
                (balanceNoFees - amountToReserve1).coerceAtLeast(0.msat)
            }
        } else {
            // The fundee doesn't pay on-chain fees.
            balanceNoFees
        }
    }

    fun availableBalanceForReceive(): MilliSatoshi {
        val reduced = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
        val balanceNoFees = (reduced.toRemote - localParams.channelReserve.toMilliSatoshi()).coerceAtLeast(0.msat)
        return if (localParams.isFunder) {
            // The fundee doesn't pay on-chain fees so we don't take those into account when receiving.
            balanceNoFees
        } else {
            // The funder always pays the on-chain fees, so we must subtract that from the amount we can receive.
            val commitFees = commitTxFeeMsat(localParams.dustLimit, reduced)
            // we expected the funder to keep a "funder fee buffer" (see explanation above)
            val funderFeeBuffer = commitTxFeeMsat(localParams.dustLimit, reduced.copy(feeratePerKw = 2 * reduced.feeratePerKw)) + htlcOutputFee(2 * reduced.feeratePerKw)
            val amountToReserve = commitFees.coerceAtLeast(funderFeeBuffer)
            if (balanceNoFees - amountToReserve < receivedHtlcTrimThreshold(localParams.dustLimit, reduced).toMilliSatoshi()) {
                // htlc will be trimmed
                (balanceNoFees - amountToReserve).coerceAtLeast(0.msat)
            } else {
                // htlc will have an output in the commitment tx, so there will be additional fees.
                val commitFees1 = commitFees + htlcOutputFee(reduced.feeratePerKw)
                // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
                val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(2 * reduced.feeratePerKw)
                val amountToReserve1 = commitFees1.coerceAtLeast(funderFeeBuffer1)
                (balanceNoFees - amountToReserve1).coerceAtLeast(0.msat)
            }
        }
    }

    fun isMoreRecent(other: Commitments): Boolean {
        return this.localCommit.index > other.localCommit.index ||
                this.remoteCommit.index > other.remoteCommit.index ||
                (this.remoteCommit.index == other.remoteCommit.index && this.remoteNextCommitInfo.isLeft && other.remoteNextCommitInfo.isRight)
    }

    /**
     * @param cmd         add HTLC command
     * @param paymentId   id of the payment
     * @param blockHeight current block height
     * @return either Failure(failureMessage) with a BOLT #4 failure or Success(new commitments, updateAddHtlc)
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun sendAdd(cmd: CMD_ADD_HTLC, paymentId: UUID, blockHeight: Long): Try<Pair<Commitments, UpdateAddHtlc>> {
        val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
        // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
        if (cmd.cltvExpiry >= maxExpiry) {
            return Try.Failure(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
        }

        // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
        val htlcMinimum = remoteParams.htlcMinimum.coerceAtLeast(1.msat)
        if (cmd.amount < htlcMinimum) {
            return Try.Failure(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = cmd.amount))
        }

        // let's compute the current commitment *as seen by them* with this change taken into account
        val add = UpdateAddHtlc(channelId, localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
        // we increment the local htlc index and add an entry to the origins map
        val commitments1 = addLocalProposal(add).copy(localNextHtlcId = localNextHtlcId + 1, payments = payments + mapOf(add.id to paymentId))
        // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
        val remoteCommit1 = when (remoteNextCommitInfo) {
            is Either.Left -> remoteNextCommitInfo.value.nextRemoteCommit
            is Either.Right -> remoteCommit
        }
        val reduced = CommitmentSpec.reduce(remoteCommit1.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
        // the HTLC we are about to create is outgoing, but from their point of view it is incoming
        val outgoingHtlcs = reduced.htlcs.incomings()

        // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
        val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
        // the funder needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
        // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
        val funderFeeBuffer = commitTxFeeMsat(commitments1.remoteParams.dustLimit, reduced.copy(feeratePerKw = 2 * reduced.feeratePerKw)) + htlcOutputFee(2 * reduced.feeratePerKw)
        // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
        // which may result in a lower commit tx fee; this is why we take the max of the two.
        val missingForSender = reduced.toRemote - commitments1.remoteParams.channelReserve.toMilliSatoshi() - (if (commitments1.localParams.isFunder) fees.toMilliSatoshi().coerceAtLeast(funderFeeBuffer) else 0.msat)
        val missingForReceiver = reduced.toLocal - commitments1.localParams.channelReserve.toMilliSatoshi() - (if (commitments1.localParams.isFunder) 0.msat else fees.toMilliSatoshi())
        if (missingForSender < 0.msat) {
            return Try.Failure(
                InsufficientFunds(
                    channelId,
                    amount = cmd.amount,
                    missing = -missingForSender.truncateToSatoshi(),
                    reserve = commitments1.remoteParams.channelReserve,
                    fees = if (commitments1.localParams.isFunder) fees else 0.sat
                )
            )
        } else if (missingForReceiver < 0.msat) {
            if (localParams.isFunder) {
                // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
            } else {
                return Try.Failure(RemoteCannotAffordFeesForNewHtlc(channelId, amount = cmd.amount, missing = -missingForReceiver.truncateToSatoshi(), reserve = commitments1.remoteParams.channelReserve, fees = fees))
            }
        }

        val htlcValueInFlight = outgoingHtlcs.map { it.amountMsat }.sum()
        if (commitments1.remoteParams.maxHtlcValueInFlightMsat < htlcValueInFlight.toLong()) {
            // TODO: this should be a specific UPDATE error
            return Try.Failure(HtlcValueTooHighInFlight(channelId, maximum = commitments1.remoteParams.maxHtlcValueInFlightMsat.toULong(), actual = htlcValueInFlight))
        }

        if (outgoingHtlcs.size > commitments1.remoteParams.maxAcceptedHtlcs) {
            return Try.Failure(TooManyAcceptedHtlcs(channelId, maximum = commitments1.remoteParams.maxAcceptedHtlcs.toLong()))
        }

        return Try.Success(Pair(commitments1, add))
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun receiveAdd(add: UpdateAddHtlc): Try<Commitments> = runTrying {
        if (add.id != remoteNextHtlcId) {
            throw UnexpectedHtlcId(channelId, expected = remoteNextHtlcId, actual = add.id)
        }

        // we used to not enforce a strictly positive minimum, hence the max(1 msat)
        val htlcMinimum = localParams.htlcMinimum.coerceAtLeast(1.msat)
        if (add.amountMsat < htlcMinimum) {
            throw HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = add.amountMsat)
        }

        // let's compute the current commitment *as seen by us* including this change
        val commitments1 = addRemoteProposal(add).copy(remoteNextHtlcId = remoteNextHtlcId + 1)
        val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)
        val incomingHtlcs = reduced.htlcs.incomings()

        // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
        val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
        // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote funder that doesn't have this mitigation in place
        // We could enforce it once we're confident a large portion of the network implements it.
        val missingForSender = reduced.toRemote - commitments1.localParams.channelReserve.toMilliSatoshi() - (if (commitments1.localParams.isFunder) 0.sat else fees).toMilliSatoshi()
        val missingForReceiver = reduced.toLocal - commitments1.remoteParams.channelReserve.toMilliSatoshi() - (if (commitments1.localParams.isFunder) fees else 0.sat).toMilliSatoshi()
        if (missingForSender < 0.sat) {
            throw InsufficientFunds(channelId, amount = add.amountMsat, missing = -missingForSender.truncateToSatoshi(), reserve = commitments1.localParams.channelReserve, fees = if (commitments1.localParams.isFunder) 0.sat else fees)
        } else if (missingForReceiver < 0.sat) {
            @Suppress("ControlFlowWithEmptyBody")
            if (localParams.isFunder) {
                throw CannotAffordFees(channelId, missing = -missingForReceiver.truncateToSatoshi(), reserve = commitments1.remoteParams.channelReserve, fees = fees)
            } else {
                // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
            }
        }

        val htlcValueInFlight = incomingHtlcs.map { it.amountMsat }.sum()
        if (commitments1.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight.toLong()) {
            throw HtlcValueTooHighInFlight(channelId, maximum = commitments1.localParams.maxHtlcValueInFlightMsat.toULong(), actual = htlcValueInFlight)
        }

        if (incomingHtlcs.size > commitments1.localParams.maxAcceptedHtlcs) {
            throw TooManyAcceptedHtlcs(channelId, maximum = commitments1.localParams.maxAcceptedHtlcs.toLong())
        }

        commitments1
    }

    fun getOutgoingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? {
        val localSigned = (remoteNextCommitInfo.left?.nextRemoteCommit ?: remoteCommit).spec.findIncomingHtlcById(htlcId) ?: return null
        val remoteSigned = localCommit.spec.findOutgoingHtlcById(htlcId) ?: return null
        require(localSigned.add == remoteSigned.add)
        return localSigned.add
    }

    fun getIncomingHtlcCrossSigned(htlcId: Long): UpdateAddHtlc? {
        val localSigned = (remoteNextCommitInfo.left?.nextRemoteCommit ?: remoteCommit).spec.findOutgoingHtlcById(htlcId) ?: return null
        val remoteSigned = localCommit.spec.findIncomingHtlcById(htlcId) ?: return null
        require(localSigned.add == remoteSigned.add)
        return localSigned.add
    }

    fun sendFulfill(cmd: CMD_FULFILL_HTLC): Try<Pair<Commitments, UpdateFulfillHtlc>> {
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Try.Failure(UnknownHtlcId(channelId, cmd.id))
        return when {
            alreadyProposed(localChanges.proposed, htlc.id) -> {
                // we have already sent a fail/fulfill for this htlc
                Try.Failure(UnknownHtlcId(channelId, cmd.id))
            }
            htlc.paymentHash.contentEquals(sha256(cmd.r)) -> {
                val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
                val commitments1 = addLocalProposal(fulfill)
                Try.Success(Pair(commitments1, fulfill))
            }
            else -> Try.Failure(InvalidHtlcPreimage(channelId, cmd.id))
        }
    }

    fun receiveFulfill(fulfill: UpdateFulfillHtlc): Try<Triple<Commitments, UUID, UpdateAddHtlc>> {
        val htlc = getOutgoingHtlcCrossSigned(fulfill.id) ?: return Try.Failure(UnknownHtlcId(channelId, fulfill.id))
        return when {
            htlc.paymentHash.contentEquals(sha256(fulfill.paymentPreimage)) -> runTrying {
                Triple(addRemoteProposal(fulfill), payments[fulfill.id]!!, htlc)
            }
            else -> Try.Failure(InvalidHtlcPreimage(channelId, fulfill.id))
        }
    }

    fun sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Try<Pair<Commitments, UpdateFailHtlc>> {
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Try.Failure(UnknownHtlcId(channelId, cmd.id))
        return when {
            alreadyProposed(localChanges.proposed, htlc.id) -> {
                // we have already sent a fail/fulfill for this htlc
                Try.Failure(UnknownHtlcId(channelId, cmd.id))
            }
            else -> {
                // we need to decrypt the payment onion to obtain the shared secret to build the error packet
                when (val result = Sphinx.peel(nodeSecret, htlc.paymentHash, htlc.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength)) {
                    is Either.Right -> {
                        val reason = when (cmd.reason) {
                            is CMD_FAIL_HTLC.Reason.Bytes -> FailurePacket.wrap(cmd.reason.bytes.toByteArray(), result.value.sharedSecret)
                            is CMD_FAIL_HTLC.Reason.Failure -> FailurePacket.create(result.value.sharedSecret, cmd.reason.message)
                        }
                        val fail = UpdateFailHtlc(channelId, cmd.id, ByteVector(reason))
                        val commitments1 = addLocalProposal(fail)
                        Try.Success(Pair(commitments1, fail))
                    }
                    is Either.Left -> Try.Failure(CannotExtractSharedSecret(channelId, htlc))
                }
            }
        }
    }

    fun sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Try<Pair<Commitments, UpdateFailMalformedHtlc>> {
        // BADONION bit must be set in failure_code
        if ((cmd.failureCode and FailureMessage.BADONION) == 0) return Try.Failure(InvalidFailureCode(channelId))
        val htlc = getIncomingHtlcCrossSigned(cmd.id) ?: return Try.Failure(UnknownHtlcId(channelId, cmd.id))
        return when {
            alreadyProposed(localChanges.proposed, htlc.id) -> {
                // we have already sent a fail/fulfill for this htlc
                Try.Failure(UnknownHtlcId(channelId, cmd.id))
            }
            else -> {
                val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
                val commitments1 = addLocalProposal(fail)
                Try.Success(Pair(commitments1, fail))
            }
        }
    }

    fun receiveFail(fail: UpdateFailHtlc): Try<Triple<Commitments, UUID, UpdateAddHtlc>> {
        val htlc = getOutgoingHtlcCrossSigned(fail.id) ?: return Try.Failure(UnknownHtlcId(channelId, fail.id))
        return runTrying { Triple(addRemoteProposal(fail), payments[fail.id]!!, htlc) }
    }

    fun receiveFailMalformed(fail: UpdateFailMalformedHtlc): Try<Triple<Commitments, UUID, UpdateAddHtlc>> {
        // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
        if ((fail.failureCode and FailureMessage.BADONION) == 0) return Try.Failure(InvalidFailureCode(channelId))
        val htlc = getOutgoingHtlcCrossSigned(fail.id) ?: return Try.Failure(UnknownHtlcId(channelId, fail.id))
        return runTrying { Triple(addRemoteProposal(fail), payments[fail.id]!!, htlc) }
    }

    fun sendFee(cmd: CMD_UPDATE_FEE): Try<Pair<Commitments, UpdateFee>> {
        if (!localParams.isFunder) return Try.Failure(FundeeCannotSendUpdateFee(channelId))
        // let's compute the current commitment *as seen by them* with this change taken into account
        val fee = UpdateFee(channelId, cmd.feeratePerKw)
        // update_fee replace each other, so we can remove previous ones
        val commitments1 = copy(localChanges = localChanges.copy(proposed = localChanges.proposed.filterNot { it is UpdateFee } + fee))
        val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        // we look from remote's point of view, so if local is funder remote doesn't pay the fees
        val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
        val missing = reduced.toRemote.truncateToSatoshi() - commitments1.remoteParams.channelReserve - fees
        if (missing < 0.sat) return Try.Failure(CannotAffordFees(channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees))
        return Try.Success(Pair(commitments1, fee))
    }

    fun receiveFee(localCommitmentFeeratePerKw: Long, fee: UpdateFee, maxFeerateMismatch: Double): Try<Commitments> {
        if (localParams.isFunder) return Try.Failure(FundeeCannotSendUpdateFee(channelId))
        if (fee.feeratePerKw < Eclair.MinimumFeeratePerKw) return Try.Failure(FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw))
        if (Helpers.isFeeDiffTooHigh(fee.feeratePerKw, localCommitmentFeeratePerKw, maxFeerateMismatch)) return Try.Failure(
            FeerateTooDifferent(
                channelId,
                localFeeratePerKw = localCommitmentFeeratePerKw,
                remoteFeeratePerKw = fee.feeratePerKw
            )
        )
        // NB: we check that the funder can afford this new fee even if spec allows to do it at next signature
        // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
        // and it would be tricky to check if the conditions are met at signing
        // (it also means that we need to check the fee of the initial commitment tx somewhere)

        // let's compute the current commitment *as seen by us* including this change
        // update_fee replace each other, so we can remove previous ones
        val commitments1 = copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed.filterNot { it is UpdateFee } + fee))
        val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        val fees = commitTxFee(commitments1.remoteParams.dustLimit, reduced)
        val missing = reduced.toRemote.truncateToSatoshi() - commitments1.localParams.channelReserve - fees
        if (missing < 0.sat) return Try.Failure(CannotAffordFees(channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees))
        return Try.Success(commitments1)
    }

    fun localHasUnsignedOutgoingHtlcs(): Boolean = localChanges.proposed.find { it is UpdateAddHtlc } != null

    fun remoteHasUnsignedOutgoingHtlcs(): Boolean = remoteChanges.proposed.find { it is UpdateAddHtlc } != null

    fun localHasChanges(): Boolean = remoteChanges.acked.isNotEmpty() || localChanges.proposed.isNotEmpty()

    fun remoteHasChanges(): Boolean = localChanges.acked.isNotEmpty() || remoteChanges.proposed.isNotEmpty()

    fun sendCommit(keyManager: KeyManager, log: Logger): Try<Pair<Commitments, CommitSig>> {
        val remoteNextPerCommitmentPoint = remoteNextCommitInfo.right ?: return Try.Failure(CannotSignBeforeRevocation(channelId))
        if (!localHasChanges()) return Try.Failure(CannotSignWithoutChanges(channelId))

        return runTrying {
            // remote commitment will includes all local changes + remote acked changes
            val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
            val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeRemoteTxs(keyManager, channelVersion, remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
            val sig = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath))

            val sortedHtlcTxs: List<TransactionWithInputInfo> = (htlcTimeoutTxs + htlcSuccessTxs).sortedBy { it.input.outPoint.index }
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val htlcSigs = sortedHtlcTxs.map { keyManager.sign(it, keyManager.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint) }

            // NB: IN/OUT htlcs are inverted because this is the remote commit
            log.info {
                "built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong()} toRemoteMsat=${spec.toRemote.toLong()} htlc_in=${
                    spec.htlcs.outgoings().map { it.id }.joinToString(",")
                } htlc_out=${spec.htlcs.incomings().map { it.id }.joinToString(",")} feeratePerKw=${spec.feeratePerKw} txid=${remoteCommitTx.tx.txid} tx=${remoteCommitTx.tx}"
            }

            // don't sign if they don't get paid
            val commitSig = CommitSig(
                channelId = channelId,
                signature = sig,
                htlcSignatures = htlcSigs.toList()
            )

            val commitments1 = copy(
                remoteNextCommitInfo = Either.Left(
                    WaitingForRevocation(
                        RemoteCommit(
                            remoteCommit.index + 1,
                            spec,
                            remoteCommitTx.tx.txid,
                            remoteNextPerCommitmentPoint
                        ), commitSig, localCommit.index, reSignAsap = true
                    )
                ),
                localChanges = localChanges.copy(proposed = emptyList(), signed = localChanges.proposed),
                remoteChanges = remoteChanges.copy(acked = emptyList(), signed = remoteChanges.acked)
            )
            Pair(commitments1, commitSig)
        }
    }

    fun receiveCommit(commit: CommitSig, keyManager: KeyManager, log: Logger): Try<Pair<Commitments, RevokeAndAck>> = runTrying {
        // they sent us a signature for *their* view of *our* next commit tx
        // so in terms of rev.hashes and indexes we have:
        // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
        // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
        // is about to become our current revocation hash
        // ourCommit.index + 2 -> which is about to become our next revocation hash
        // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
        // and will increment our index

        // lnd sometimes sends a new signature without any changes, which is a (harmless) spec violation
        if (!remoteHasChanges()) {
            //  throw CannotSignWithoutChanges(commitments.channelId)
            log.warning { "received a commit sig with no changes (probably coming from lnd)" }
        }

        // check that their signature is valid
        // signatures are now optional in the commit message, and will be sent only if the other party is actually
        // receiving money i.e its commit tx has one output for them

        val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
        val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, localCommit.index + 1)
        val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeLocalTxs(keyManager, channelVersion, localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)
        val sig = keyManager.sign(localCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath))

        log.info {
            "built local commit number=${localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong()} toRemoteMsat=${spec.toRemote.toLong()} htlc_in=${
                spec.htlcs.incomings().map { it.id }.joinToString(",")
            } htlc_out=${spec.htlcs.outgoings().map { it.id }.joinToString(",")} feeratePerKw=${spec.feeratePerKw} txid=${localCommitTx.tx.txid} tx=${localCommitTx.tx}"
        }

        // TODO: should we have optional sig? (original comment: this tx will NOT be signed if our output is empty)

        // no need to compute htlc sigs if commit sig doesn't check out
        val signedCommitTx = Transactions.addSigs(localCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, sig, commit.signature)
        when (val check = Transactions.checkSpendable(signedCommitTx)) {
            is Try.Failure -> {
                log.error(check.error) { "remote signature $commit is invalid" }
                throw InvalidCommitmentSignature(channelId, signedCommitTx.tx)
            }
        }

        val sortedHtlcTxs: List<TransactionWithInputInfo> = (htlcTimeoutTxs + htlcSuccessTxs).sortedBy { it.input.outPoint.index }
        if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
            throw HtlcSigCountMismatch(channelId, sortedHtlcTxs.size, commit.htlcSignatures.size)
        }
        val htlcSigs = sortedHtlcTxs.map { keyManager.sign(it, keyManager.htlcPoint(channelKeyPath), localPerCommitmentPoint) }
        val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
        // combine the sigs to make signed txes
        val htlcTxsAndSigs = Triple(sortedHtlcTxs, htlcSigs, commit.htlcSignatures).zipped().mapNotNull { (htlcTx, localSig, remoteSig) ->
            when (htlcTx) {
                is HtlcTimeoutTx -> {
                    if (Transactions.checkSpendable(Transactions.addSigs(htlcTx, localSig, remoteSig)).isFailure) {
                        throw InvalidHtlcSignature(channelId, htlcTx.tx)
                    }
                    HtlcTxAndSigs(htlcTx, localSig, remoteSig)
                }
                is HtlcSuccessTx -> {
                    // we can't check that htlc-success tx are spendable because we need the payment preimage; thus we only check the remote sig
                    if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey)) {
                        throw InvalidHtlcSignature(channelId, htlcTx.tx)
                    }
                    HtlcTxAndSigs(htlcTx, localSig, remoteSig)
                }
                else -> null
            }
        }

        // we will send our revocation preimage + our next revocation hash
        val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, localCommit.index)
        val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, localCommit.index + 2)
        val revocation = RevokeAndAck(
            channelId = channelId,
            perCommitmentSecret = localPerCommitmentSecret,
            nextPerCommitmentPoint = localNextPerCommitmentPoint
        )

        // update our commitment data
        val localCommit1 = LocalCommit(
            index = localCommit.index + 1,
            spec,
            publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs)
        )
        val ourChanges1 = localChanges.copy(acked = emptyList())
        val theirChanges1 = remoteChanges.copy(proposed = emptyList(), acked = remoteChanges.acked + remoteChanges.proposed)
        val commitments1 = copy(localCommit = localCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

        Pair(commitments1, revocation)
    }

    fun receiveRevocation(revocation: RevokeAndAck): Try<Pair<Commitments, List<ChannelAction>>> {
        val theirNextCommit = remoteNextCommitInfo.left?.nextRemoteCommit ?: return Try.Failure(UnexpectedRevocation(channelId))
        if (revocation.perCommitmentSecret.publicKey() != remoteCommit.remotePerCommitmentPoint) return Try.Failure(InvalidRevocation(channelId))

        // the outgoing following htlcs have been completed (fulfilled or failed) when we received this revocation
        // they have been removed from both local and remote commitment
        // (since fulfill/fail are sent by remote, they are (1) signed by them, (2) revoked by us, (3) signed by us, (4) revoked by them
        val completedOutgoingHtlcs = remoteCommit.spec.htlcs.incomings().map { it.id } - theirNextCommit.spec.htlcs.incomings().map { it.id }
        // we remove the newly completed htlcs from the origin map
        val payments1 = payments - completedOutgoingHtlcs
        val actions: MutableList<ChannelAction> = ArrayList<ChannelAction>().toMutableList()
        remoteChanges.signed.forEach {
            when (it) {
                is UpdateAddHtlc -> actions += ProcessAdd(it)
                is UpdateFailHtlc -> actions += ProcessFail(it)
                is UpdateFailMalformedHtlc -> actions += ProcessFailMalformed(it)
                else -> Unit
            }
        }
        val commitments1 = this.copy(
            localChanges = localChanges.copy(signed = emptyList(), acked = localChanges.acked + localChanges.signed),
            remoteChanges = remoteChanges.copy(signed = emptyList()),
            remoteCommit = theirNextCommit,
            remoteNextCommitInfo = Either.Right(revocation.nextPerCommitmentPoint),
            remotePerCommitmentSecrets = remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - remoteCommit.index),
            payments = payments1
        )
        return Try.Success(Pair(commitments1, actions.toList()))
    }

    companion object {

        fun alreadyProposed(changes: List<UpdateMessage>, id: Long): Boolean = changes.any {
            when (it) {
                is UpdateFulfillHtlc -> id == it.id
                is UpdateFailHtlc -> id == it.id
                is UpdateFailMalformedHtlc -> id == it.id
                else -> false
            }
        }

        fun revocationPreimage(seed: ByteVector32, index: Long): ByteVector32 = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFL - index)

        fun revocationHash(seed: ByteVector32, index: Long): ByteVector32 = ByteVector32(sha256(revocationPreimage(seed, index)))

        fun makeLocalTxs(
            keyManager: KeyManager,
            channelVersion: ChannelVersion,
            commitTxNumber: Long,
            localParams: LocalParams,
            remoteParams: RemoteParams,
            commitmentInput: Transactions.InputInfo,
            localPerCommitmentPoint: PublicKey,
            spec: CommitmentSpec
        ): Triple<CommitTx, List<HtlcTimeoutTx>, List<HtlcSuccessTx>> {
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
            val remotePaymentPubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
            val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
            val outputs = makeCommitTxOutputs(localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)
            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, keyManager.paymentPoint(channelKeyPath).publicKey, remoteParams.paymentBasepoint, localParams.isFunder, outputs)
            val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.feeratePerKw, outputs)
            return Triple(commitTx, htlcTimeoutTxs, htlcSuccessTxs)
        }

        fun makeRemoteTxs(
            keyManager: KeyManager,
            channelVersion: ChannelVersion,
            commitTxNumber: Long, localParams: LocalParams,
            remoteParams: RemoteParams, commitmentInput: Transactions.InputInfo,
            remotePerCommitmentPoint: PublicKey,
            spec: CommitmentSpec
        ): Triple<CommitTx, List<HtlcTimeoutTx>, List<HtlcSuccessTx>> {
            val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
            val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, spec)
            val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, keyManager.paymentPoint(channelKeyPath).publicKey, !localParams.isFunder, outputs)
            val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.feeratePerKw, outputs)
            return Triple(commitTx, htlcTimeoutTxs, htlcSuccessTxs)
        }
    }
}