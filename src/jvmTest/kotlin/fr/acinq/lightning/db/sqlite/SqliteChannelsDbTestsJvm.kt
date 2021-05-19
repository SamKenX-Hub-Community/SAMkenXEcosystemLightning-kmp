package fr.acinq.lightning.db.sqlite

import fr.acinq.lightning.channel.ChannelVersion
import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals

class SqliteChannelsDbTestsJvm : LightningTestSuite() {
    fun sqliteInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @Test
    fun `basic tests`() {
        runBlocking {
            val db = SqliteChannelsDb(TestConstants.Alice.nodeParams, sqliteInMemory())
            val (alice, _) = TestsHelper.reachNormal(ChannelVersion.STANDARD, 1, 1000000.sat)
            db.addOrUpdateChannel(alice)
            val (bob, _) = TestsHelper.reachNormal(ChannelVersion.STANDARD, 2, 2000000.sat)
            db.addOrUpdateChannel(bob)
            assertEquals(db.listLocalChannels(), listOf(alice, bob))
        }
    }
}