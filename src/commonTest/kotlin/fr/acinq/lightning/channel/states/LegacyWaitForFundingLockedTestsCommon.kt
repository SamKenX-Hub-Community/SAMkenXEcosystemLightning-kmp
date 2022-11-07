package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEEPLYBURIED
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.*

import fr.acinq.lightning.serialization.Serialization
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.EncryptedChannelData
import fr.acinq.lightning.wire.Init
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegacyWaitForFundingLockedTestsCommon {
    @Test
    fun `restore legacy channel`() {
        // This data was generated with lightning-kmp v1.1.0
        val waitForFundingLocked = ByteVector.fromHex(
            "d236a18c976d566711632eec6c9aef3eeac1494e188e16fe6950e40969c6320549eac3c8845c7c3cb7f3cba5675fc5f694322b8b823f27e913ffb90a7589e9841751470e293ce7ce37bd6c32a958e9050cc93a76e6cccc925ab50e1c328171404a17609dc407ef93805197ac59cc3793ff6f171604ff5eb2ec4e9b3391611a3a49e94994268a3939a0272b0efad40848105bbd6284092d801ddc19ea0cc7cc25d21de905492b0155ee6790b050fb19804f502d81a0b643eeadc327f4347cd85f67cc990830aa9ae9eb698b47eaaef4344ea36162f028887f3872e1ecae8fbd59f55f8443ee8340ff62e2368fc7414b7a650011ac943e184184b20efb937a05d6255e4deb8eb93479f6a7b8834bbbbf96e2effe58497b8e1015520101ccc412ba4c88676fd985e922f7d2e400cca8ffc5f2c4d3b646f5e2ef7a78c6377a7b684fc99816c6433ef01edd887b5ae03c7c538599a44f157f851757ec167845c00b74995b3dd534df7f9a94f20835c0ed8b571935a2614412bf6374b92ff0b1676ca11b2ead8ce3b477066d8b6a96f58ff155c9868190071a48ece842df32451e5a68b3ca559d8972e1a471d00b5906d48fa8a9a4d450a5f3eca192daa1a45cf5d5b9f65216becc0a0876d1f4081fecad201a99442406bad01319c4b4220076d0f6ee1b11d31ab1c47e8954f6b5a2468ad17b89088bba828c2f24ccfc9a011e6d25b050c78a685e96f7e56896b2eafe34e0399f5703cc35c1f65112506625bae00b1e2ac6dcef6e8245bea7e888b98369107929185e1fad0b97c4f00b28d50b30c1ef7fd33e51f1dcc32cf88bb575b23ce04e9c4f927a6fbb0a8a027ba81977cfcca8f3b6684c74c5ba7c8efc72b3844f5fc503f60eb1a8b3e45edbc16708b570e33ffe3e01f427e77b37d4dc95742149ea57e5e0c7abd78a7b8b39e64bd035d14ebe370d9eee4952fcaab6a1f0713dae2e8dd67eb99fed580147172ab465aba5ea5968adf017c7b158fcde85694c32adadd96f5a9e19e73a1abd8ba388fd764129186e883df7c581c076e3996772b78cd730042fa040c0728e6b5da87e080c088ac490bbe8bba226f2b28c20d8c5407f0291668b1dfabfab276cc02f4c0bbe3c950a1b8d7b47f8b04e180e5bf9c46d05775c6662762e17ecf561ca3f72af7cad4131508bd979c2505b0d37c2de7a826b64f77c41fe24b2d16182b2f8836900aeef83b8e7841cb408963eed0162e173a7846207a27f8b178dffd3c8ee8ac2e98e6f08e420a1b8baa3d2532c51bf47b72e1624f8a1032adf06099179425dbb78d394da9dbd5e2b0b3bb23b27b53a0adc139a092d6e8a04cabcba004e3de7f4a8ac716bf1b9b040e6d13ac186d2a9d4ed4d2876ae1c98ee50569cf73e780306af6b28961f6213fbcf3f0e692e4bd77b7da5ae73146090f4efc4b6a2d9e906f13e9781b6e888fa90d14a16117383981017a974c86ddbb367667ce4a672abc45555139548957b2f21ca155a91697944f2f5aa8caabe93b98015a43cbb68ef26962d3e83110434c93287bbd36d5f513a23960cd923a0a07c4705537e8a6afb36d8459cfb3963144dd1431869eaa93c02774ddb3da8aca8599997a7b4dcb53033e3d5f9ed62cc9af49abef77483145913c067428b87c3f1149269029e19fe8027baf15c0830cc731124021372c56be2fb031a83d6f2594ddb534245052cd06caa90315e3cf1e45e9f3773b2a20302cc39ba83a743bb428d8bc7b2db8bc93ac8c48938f06f9e5d41f4d1c4ed9de149b797bf339bd77301c899b76910c8cd8333d48d365aee1af80eaa994bf18ce04b0c59931a97c64724dcf01a8372a807efa0b5bf8f8cc00396c3bbb5d5ce0bc8cf8bc98f0734b9c8c030ffe3e9fa71c9807d9417862cfb6740658e01978170b4f7ad314f0fbe0987206579ec65a5d1e57b9a43523104c507ced68624ff440642be208ca3eb655a8d068dc74f12ed334092d086b7a83bef03d2e756ae0c839024c505265ff20ba06def8f928a9072ee72adea0cd5694b524b15e436e5e8b88022f5c4b00a1cb1bd3a4cfc1ec02142349c1292f4236b7c18383162ea4d50132dbc46e528b092e8fb7b436415d6f4d2b23017c59f9243aef6411a22744c0cb7497e1af3dc7964cbb5d1dc478d9be64d01719cb8e2017d4354ca3b373bf7756e3f0f54ef93cb5eb56e80720d6a7b1e687010f2a76bd9ae8003c25a2e6cf99450bc067cda702ba3dd35a848bfd6da2c57f8784e6165043228118c4dc1b06d2dbfddea813074a4a1813b16b75dd0e55066545378c33a8cabe0069488a90a0891f433241131d94ee85d76cdf9bdc59fac4ddb305512c566eaf33c5bef4516395b3639750d5eb0988e09ed2d46eb593e1912f9667cbabd816eb600e98e37263cf48dbf5cb306caf02f62a5a43fe2e797ea491cc0d5d60bd15aa455611e1ed5df96ba76a7facec2fcb81918c254f61ac87c9d398cea5f1d81dd594eb94583fd9481ef38531dfa7d159ec59af5b7eaf95c4e3025e84e5b348c38486fa2570ae9b2deea38f267581c4eaaee3d4b7fcb68899c9363d0e880a5d7cd76702c9c549bed92f4c2c0d900453f2b6e36cc174e69ad65d775258bc46f2cc003768788f9b9aa3892890ce704301c78d9f84c7499442d64866849187ed43b5f68def04f9ed345d915055794e4e36f0b775027bd8a11ebbf2e2690f96c6058bf0709c612d6ff762ba40804bc03655a6e535545a07392842ee4bd802c4f44ac93e13601f047f1790fb2b3c16c30833a8b104824b99758e058218ed1d15abd3044e3b690eda2d5a360be6508a3cd1133f68a9693ecbf24da8999c6eec2fd6d5322953d3c6e608d5a2186e2ea56fc046b7e029acce56828c665d3b9b760418e6401edb636116a5ac867791620bb13dfeed4a28536217dd0c81fddf6d0166d7cd45d57f362e40df68bb279e16075d764963339129e39c44816dffe423441b2c964246e6c5e26cb220258ef88cf696e48c10781eb834903c584af0bb5d5cac591dbc2354ea57a7535b989e431b9e3bc301a8d08cb127b97a5e8dc732d274fb413a5d6e69811e1bd5eb77db27c1f85d221325b06a078688493a13de832e5be50ac514153cd9a582e3edef18751aa30d153717ab580fb12a6b3d704dc953b5b06b4f45bbc177f2a91ae084cfc28e2dd8dc7a1e05240e80995d3afc3ab8f4ed80e769a842e6eaeb0ad5a6868f6634632358afdf1ee51f3b1b4e4a7b2550c63a287f5033767a2683f81d206f938120c86afabc95a625b641bd883cfde6fc8555fca3681f130bfe3287284e93e2072b83fdbb9b3198744d5040a5fff3753a9bc9aaeca1938cec63bb342845af692701b8acc4433308f4a75672a5deb6e52b97d64024b1b2fd7fe44c057569991f53d4fb60b0501d7ac7cc7971a67ced33da1c049c364fea91af91469de4e72a51efb11ec6b1ac5cde7e1290a8c389289f991e7c44013f62bd941a6fe2b1ba901f17d7b9d3d1ca9ecce7c017c3aebc117971a57c261dfc8bde79320fa25139b76a5c67cbd432e0bb8afda88d2e7741cb7359211bc78ccd0358ffff7e803eacb6b373468705579bacd73c748219c2749801a3515993eaf77c749935adb9a5730ee88428cad6d856a918c58ab199ad653899c3fe598ff2171f24cfcbdc16f8ed7e5ce1f04db7f541827b335eac929fb01443783f68152f8044636c78b35413f6d900a2d1115d5c960a943b88ed96c28e2ba10ae5b878be89094194bd94eb104d5be699b1778deeafa7ba7c7ae5ddd3c79487a56532f66d9a1dcc63dcb67209997d5587ea8f99921d1e500c8ddab5db2556ea329f73e0a38224652394ae5eb9384b87e14f42d0ed178667d19569142d7fd712246529d59ec54f9375358bc7c4d4d018106be268d0d83a8ff447403377845036f69440ef2c975eeb98142e1735000d85c3b8a53225c90c6008ed0751263ac2f72e6a5e2dfb8c52a88e93e23b13c39ce8d1c45a673c4ee5b33e9e4b03c58eb9a34003728201e917e2ae2336cc0127862c0d657bada3e371bf67fc63197658b55597ac0a3cad1ad2fb3ef7836a7a120e79f7ef7944be3f47da92f84ac2b2b856efd151605429235db5ca4d0d42f206af47243d70f71cc3d0f497f286f195fb264b16793e27e9d3010628f464fdb0ec4dcb433c52396107e8710e6d537161f6b78dcd2a89a8d6f335e2284741e3a0f3da06104a7c500e5f724112d5492434bd39d643418f20abbde6dc5a8cfbca7ac6d6ac9beb8bf97422bf0284dab19e94e8e7645952a6cd7370e1e49178ad2192bd00cbadc3668fa37e989c7713a9a655e7bec12f59a5ee1d9282e4a58bec73cd338c363919c062a2b8aaee5176a3580e1cb731b3a7150ce78ebcc5944e22a0ef0e13f11c81ec8b934402a497cde5bf4f658ccd93a6c9d0779157994eac86ea8a596b7746af85911b13ede1c6e12d92615fff12deb0348f8e712999ab4e8f2e0390c1867fbe642672e8e9588db48624859731e9ce3fa12ef43c35b8392a966167c068e24f6a838552e592ac8e0bc75363215e2ad801fbff53f1ad8f4eca7c67cd6d5576ab239ec7902735f2ae7287c37bf97501610541f7bae47edf2d91a3fe2dbb410d26a832ede200f5a9dade21664ea5a3b58f1467575a3238b0faaceab0827e237b1325d0ba54a2898179527e7b506ce21f298e8871988f8d66d2d6415de2b31b9d0cea349242e994b012d7986fa3c2ae34fb49e8bf448fbb4e0cf35bfcb553e74a259b33fb286b1cc9da883409857514f29f741267f0120481c0f85ccf571808dbf56268f9f33a381cf9a4418d8b6bfc4e64ef18c02e3276d479e84d7021407acd446564103dfb53e62789b21253fd6b3142ab79e0fa7f64b34074dba79815d67d9eaa48974b0a17d03e56bad2dec8ba5f0641cd1d0a92138f6cc1ce44cb1576144ce69ad2955b3b704ab31f581b5429a34fea915654d9ee688415e3e044344b4ae4593db8febb189748d59bf0aa8f28083929a0abe88dd3c238bfd8a145fdc8bacacf71a906ed99af62b2876286dae6f4729f9edbe32767c7b7f3c42a7f24afbbfa776122eb6d52af6af609415a5b11a15719641a6381650639845420ff929f1233363a26be1165d777e2b0a6d5e4e978f7dbb"
        )
        val state = PersistedChannelState.from(TestConstants.Bob.nodeParams.nodePrivateKey, EncryptedChannelData(waitForFundingLocked))
        assertIs<LegacyWaitForFundingLocked>(state)
        val fundingTx = Transaction.read("020000000100000000000000000000000000000000000000000000000000000000000000000000000000ffffffff0140420f0000000000220020f9aafa9be1212d0d373760c279f3817f9be707d674cae5f38bb31c1fd85c202900000000")
        assertEquals(state.commitments.fundingTxId, fundingTx.txid)
        val ctx = ChannelContext(
            StaticParams(TestConstants.Bob.nodeParams, TestConstants.Alice.keyManager.nodeId),
            TestConstants.defaultBlockHeight,
            OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)
        )
        val (state1, actions1) = LNChannel(ctx, WaitForInit).process(ChannelCommand.Restore(state))
        assertIs<LNChannel<Offline>>(state1)
        assertEquals(actions1.size, 1)
        val watchSpent = actions1.findWatch<WatchSpent>()
        assertEquals(watchSpent.event, BITCOIN_FUNDING_SPENT)
        assertEquals(watchSpent.txId, fundingTx.txid)
        // Reconnect to our peer.
        val localInit = Init(state.commitments.localParams.features.toByteArray().byteVector())
        val remoteInit = Init(state.commitments.remoteParams.features.toByteArray().byteVector())
        val (state2, actions2) = state1.process(ChannelCommand.Connected(localInit, remoteInit))
        assertIs<LNChannel<Syncing>>(state2)
        assertTrue(actions2.isEmpty())
        val channelReestablish = ChannelReestablish(
            state.channelId,
            state.commitments.remoteCommit.index + 1,
            state.commitments.localCommit.index,
            PrivateKey(ByteVector32.Zeroes),
            randomKey().publicKey()
        )
        val (state3, actions3) = state2.process(ChannelCommand.MessageReceived(channelReestablish))
        assertEquals(state, state3.state)
        assertEquals(actions3.size, 2)
        actions3.hasOutgoingMessage<ChannelReestablish>()
        actions3.hasOutgoingMessage<ChannelReady>()
        // Our peer sends us funding_locked.
        val (state4, actions4) = state3.process(ChannelCommand.MessageReceived(ChannelReady(state.channelId, randomKey().publicKey())))
        assertIs<LNChannel<Normal>>(state4)
        assertEquals(actions4.size, 2)
        val watchConfirmed = actions4.hasWatch<WatchConfirmed>()
        assertEquals(watchConfirmed.event, BITCOIN_FUNDING_DEEPLYBURIED)
        assertEquals(watchConfirmed.txId, fundingTx.txid)
        actions4.has<ChannelAction.Storage.StoreState>()
    }
}