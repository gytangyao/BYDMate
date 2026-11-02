package com.bydmate.app.data.mysql

import com.bydmate.app.data.autoservice.AdbVerdictJournal
import com.bydmate.app.data.charging.CatchUpJournal
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The ADB verdict explains a missing snapshot, so it cannot ride on snapshots: four days of the
 * channel being down (2026-10-28) were four days with no `car_state` row to carry the reason. These
 * tests own the shipping rules for the one row that replaces it — prefs to `app_logs`, with no
 * sample, no binder call and no logcat line involved.
 */
class AdbVerdictShippingTest {

    private class FakeSettingsDao : SettingsDao {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    /** A `logcat -d` that answers nothing: the heartbeat must ship even with no log line in sight. */
    private class EmptyDumpProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun exitValue(): Int = 0
    }

    private val settings = SettingsRepository(FakeSettingsDao(), mockk(relaxed = true))
    private val catchUp = CatchUpJournal(settings)
    private val verdicts = AdbVerdictJournal(settings)
    private val collector = CarLogCollector(settings, catchUp, verdicts).apply { exec = { EmptyDumpProcess() } }

    private suspend fun publish(line: String, sinceMs: Long = START_MS) = verdicts.record(line, sinceMs)

    @Test
    fun `a dead channel ships as one warning row with no logcat line and no sample`() = runTest {
        publish("verdict=NOT_ENABLED healthy=0 connected=0 restore=Disabled wifi=0 everAlive=0 model=DiLink5")

        val row = collector.pendingBatch().single()
        assertEquals(CarLogCollector.VERDICT_TAG, row.tag)
        assertEquals("a channel down is worth filtering for", "W", row.level)
        assertTrue("the heartbeat must not move the logcat cursor", row.marker)
        assertTrue(row.msg, row.msg.startsWith("adb verdict: verdict=NOT_ENABLED"))
        assertTrue("the phone needs to know how long it has been down", row.msg.contains("since=$START_MS"))
        assertTrue("app_logs has no device column", row.msg.contains("model=DiLink5"))
        assertTrue(row.runId, row.runId.isNotEmpty())
    }

    @Test
    fun `a healthy channel ships as info`() = runTest {
        publish("verdict=OK healthy=1 connected=0 restore=NotNeeded wifi=1 everAlive=1 model=DiLink5")
        assertEquals("I", collector.pendingBatch().single().level)
    }

    @Test
    fun `nothing ships before the first verdict`() = runTest {
        assertEquals(emptyList<MysqlLogRecord>(), collector.pendingBatch())
    }

    @Test
    fun `an unchanged verdict ships once, then again an hour later`() = runTest {
        publish("verdict=OFF_AFTER_REBOOT healthy=0 connected=0 restore=Failed reason=port_closed wifi=0 everAlive=1 model=DiLink5")
        collector.markShipped(collector.pendingBatch())
        assertEquals(emptyList<MysqlLogRecord>(), collector.pendingBatch())

        // A heartbeat that stopped arriving is a car that stopped shipping, so an unchanged verdict
        // still has to be repeated — but on the hour, not on every 30 s flush.
        assertEquals(emptyList<MysqlLogRecord>(), collector.adbVerdictBatch(System.currentTimeMillis() + 60_000L))
        assertEquals(1, collector.adbVerdictBatch(System.currentTimeMillis() + CarLogCollector.VERDICT_RESHIP_MS).size)
    }

    @Test
    fun `a verdict that changed ships at once`() = runTest {
        publish("verdict=NOT_ENABLED healthy=0 connected=0 restore=Disabled wifi=0 everAlive=0 model=DiLink5")
        collector.markShipped(collector.pendingBatch())

        publish("verdict=OK healthy=1 connected=1 restore=Restored wifi=1 everAlive=1 model=DiLink5", START_MS + 3_600_000L)
        val msg = collector.pendingBatch().single().msg
        assertTrue(msg, msg.contains("verdict=OK healthy=1"))
        assertTrue(msg, msg.contains("since=${START_MS + 3_600_000L}"))
    }

    @Test
    fun `the heartbeat costs the day budget and the cursor nothing`() = runTest {
        publish("verdict=NO_ACCESS healthy=0 connected=0 restore=NeedsDialog wifi=0 everAlive=1 model=DiLink5")
        collector.markShipped(collector.pendingBatch())

        assertEquals("0", settings.getString(SettingsRepository.KEY_MYSQL_LOG_CURSOR_TS, "unset"))
        assertEquals("0", settings.getString(SettingsRepository.KEY_MYSQL_LOG_DAY_ROWS, "unset"))
    }

    @Test
    fun `the heartbeat and the charge ring are two rows, not one`() = runTest {
        catchUp.append("STILL_CHARGING gun=1", now = START_MS)
        publish("verdict=NOT_ENABLED healthy=0 connected=0 restore=Disabled wifi=0 everAlive=0 model=DiLink5")

        val batch = collector.pendingBatch()
        assertEquals(2, batch.size)
        assertEquals(CarLogCollector.JOURNAL_TAG, batch[0].tag)
        assertEquals(CarLogCollector.VERDICT_TAG, batch[1].tag)
    }

    private companion object {
        /** Any fixed instant in the recent past: what `since=` is meant to carry. */
        const val START_MS = 1_760_000_000_000L
    }
}
