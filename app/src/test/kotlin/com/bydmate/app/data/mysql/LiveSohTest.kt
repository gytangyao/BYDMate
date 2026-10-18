package com.bydmate.app.data.mysql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSohTest {

    @Test fun `live BMS reading wins over the newest snapshot`() {
        assertEquals(94.0, resolveSohPct(94f, 88.0)!!, 0.001)
    }

    @Test fun `no snapshot is needed any more for a car that never finished a charge`() {
        assertEquals(96.5, resolveSohPct(96.5f, null)!!, 0.001)
    }

    @Test fun `a live read that is not a health figure falls back to the snapshot`() {
        // The soh fid answers these during the recalibration after a full charge.
        assertEquals(88.0, resolveSohPct(0f, 88.0)!!, 0.001)
        assertEquals(88.0, resolveSohPct(49.9f, 88.0)!!, 0.001)
        assertEquals(88.0, resolveSohPct(101f, 88.0)!!, 0.001)
    }

    @Test fun `a snapshot outside the window publishes nothing`() {
        assertNull(resolveSohPct(null, 12.0))
    }

    @Test fun `nothing readable means no value, not zero`() {
        assertNull(resolveSohPct(null, null))
        assertNull(resolveSohPct(-1f, null))
    }
}
