package com.mnbt.app.domain.model

import com.mnbt.app.data.remote.api.cloud.CloudTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 能力协商与时间解析的纯逻辑测试（协议 §6 与时间字段约定）。 */
class CloudModelsTest {

    @Test
    fun capabilities_nullList_isFull() {
        // 服务端未下发 capabilities：按全能力处理（宽容旧实现）
        assertEquals(CloudSiteCapabilities.FULL, CloudSiteCapabilities.fromList(null))
    }

    @Test
    fun capabilities_emptyList_disablesAllOptionals() {
        val caps = CloudSiteCapabilities.fromList(emptyList())
        assertFalse(caps.hardPower)
        assertFalse(caps.vnc)
        assertFalse(caps.flow)
        assertFalse(caps.credentials)
        assertFalse(caps.configText)
    }

    @Test
    fun capabilities_partialList_mapsByKnownNames() {
        val caps = CloudSiteCapabilities.fromList(listOf("vnc", "flow"))
        assertTrue(caps.vnc)
        assertTrue(caps.flow)
        assertFalse(caps.hardPower)
        assertFalse(caps.credentials)
        assertFalse(caps.configText)
    }

    @Test
    fun time_iso8601_parsesToEpochMs() {
        assertEquals(1_760_076_800_000L, CloudTime.parseEpochMs("2025-10-10T06:13:20.000Z"))
        // 空格分隔形态归一为 T 再解析
        assertEquals(1_760_076_800_000L, CloudTime.parseEpochMs("2025-10-10 06:13:20"))
    }

    @Test
    fun time_epochStrings_secondsVsMilliseconds() {
        assertEquals(1_793_145_212_000L, CloudTime.parseEpochMs("1793145212")) // 秒（<=11 位）
        assertEquals(1_793_145_212_000L, CloudTime.parseEpochMs("1793145212000")) // 毫秒
    }

    @Test
    fun time_garbage_returnsNull() {
        assertNull(CloudTime.parseEpochMs(null))
        assertNull(CloudTime.parseEpochMs(""))
        assertNull(CloudTime.parseEpochMs("not-a-time"))
    }
}
