package com.bydmate.app.data.mysql

import com.bydmate.app.data.charging.CatchUpJournal
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The charging verdicts the WARN threshold cannot deliver ship as their own row. These tests own the
 * rules that make a whole ring safe to send: one row per change, nothing re-sent that the hub already
 * has, and the newest entries are the ones that survive when the ring outgrows the column.
 */
class ChargeJournalShippingTest {

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

    /** A `logcat -d` that answers nothing: the ring has to ship on its own, without a log line in sight. */
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
    private val journal = CatchUpJournal(settings)
    private val collector = CarLogCollector(settings, journal).apply { exec = { EmptyDumpProcess() } }

    private suspend fun writeRing(vararg verdicts: String) {
        verdicts.forEachIndexed { i, v -> journal.append(v, now = START_MS + i * 1000L) }
    }

    @Test
    fun `the ring ships as one line when no log line crossed the threshold`() = runTest {
        writeRing(
            "NO_DELTA soc=98 prev=98 km=25215.4 prevKm=25215.0 odoMoved=false pending=false",
            "STILL_CHARGING gun=1",
        )
        val batch = collector.pendingBatch()
        assertEquals(1, batch.size)
        val row = batch.single()
        assertEquals(CarLogCollector.JOURNAL_TAG, row.tag)
        assertEquals("I", row.level)
        assertTrue("the ring must not move the logcat cursor", row.marker)
        assertTrue(row.msg, row.msg.startsWith("catch-up verdicts: "))
        assertTrue(row.msg, row.msg.contains("NO_DELTA soc=98 prev=98 km=25215.4"))
        assertTrue(row.msg, row.msg.indexOf("NO_DELTA") < row.msg.indexOf("STILL_CHARGING gun=1"))
    }

    @Test
    fun `verdicts the hub already has are not sent again`() = runTest {
        writeRing("STILL_CHARGING gun=1")
        val first = collector.pendingBatch()
        assertEquals(1, first.size)
        collector.markShipped(first)
        assertEquals(emptyList<MysqlLogRecord>(), collector.pendingBatch())

        // The same decision hours later: the ring's timestamp moved and its (xN) grew, but nothing
        // was decided, so the hub has the whole story already.
        journal.append("STILL_CHARGING gun=1", now = START_MS + 3_600_000L)
        assertEquals(emptyList<MysqlLogRecord>(), collector.pendingBatch())
    }

    @Test
    fun `a new verdict resends the ring, newest last`() = runTest {
        writeRing("BASELINE_INITIALIZED soc=42")
        collector.markShipped(collector.pendingBatch())
        journal.append("SESSION_CREATED kwh=8.40 src=autoservice_soc_estimate type=AC soc=42->100", now = START_MS + 600_000L)

        val msg = collector.pendingBatch().single().msg
        assertTrue(msg, msg.contains("BASELINE_INITIALIZED soc=42"))
        assertTrue(msg, msg.contains("SESSION_CREATED kwh=8.40"))
        assertTrue(msg, msg.indexOf("BASELINE") < msg.indexOf("SESSION_CREATED"))
    }

    @Test
    fun `an unconfirmed batch is offered again`() = runTest {
        writeRing("UNAVAILABLE")
        val first = collector.pendingBatch().single()
        val second = collector.pendingBatch().single()
        assertEquals(first.msg, second.msg)
        assertTrue(first.runId, first.runId.isNotEmpty())
    }

    @Test
    fun `when the ring outgrows the column the newest verdicts are the ones kept`() = runTest {
        val verdicts = (1..CatchUpJournal.MAX_ENTRIES).map {
            "NO_DELTA soc=${it % 100} prev=${(it - 1) % 100} km=252${it}.4 prevKm=251${it}.0 odoMoved=true pending=false"
        }
        writeRing(*verdicts.toTypedArray())
        val msg = collector.pendingBatch().single().msg
        assertTrue("got ${msg.length}", msg.length <= MysqlUpsertSql.MAX_LOG_MSG_CHARS)
        assertTrue(msg, msg.contains(verdicts.last()))
        assertFalse(msg, msg.contains(verdicts.first()))
    }

    private companion object {
        /** Any fixed instant in the recent past: the ring stamps its entries from this. */
        const val START_MS = 1_760_000_000_000L
    }
}
