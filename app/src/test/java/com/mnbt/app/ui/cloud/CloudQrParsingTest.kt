package com.mnbt.app.ui.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云站点配对二维码解析（CloudPairScanScreen.parseCloudQr）的三分支契约，见 docs/cloud-integration.md §2。 */
class CloudQrParsingTest {

    @Test
    fun v2ValidQr_parsesUrlCodeAndType() {
        val qr = parseCloudQr(
            """{"app":"mnbt","type":"zjmf","proto":2,"url":"https://idc.example.com","code":"A1B2C3D4E5"}""",
        )
        assertTrue(qr is CloudQr.Valid)
        qr as CloudQr.Valid
        assertEquals("https://idc.example.com", qr.url)
        assertEquals("A1B2C3D4E5", qr.code)
        assertEquals("zjmf", qr.systemType)
    }

    @Test
    fun v2ValidQr_typeIsFreeFormSystemId() {
        // v2 起 type 不再限定 zjmf，服务商自由填写
        val qr = parseCloudQr(
            """{"app":"mnbt","type":"my-whmcs-panel","proto":2,"url":"http://1.2.3.4/panel","code":"XYZ"}""",
        )
        assertTrue(qr is CloudQr.Valid)
        assertEquals("my-whmcs-panel", (qr as CloudQr.Valid).systemType)
    }

    @Test
    fun protoAsNumber_isAccepted() {
        val qr = parseCloudQr(
            """{"app":"mnbt","type":"zjmf","proto":2,"url":"https://a.com","code":"C1"}""",
        )
        assertTrue(qr is CloudQr.Valid)
    }

    @Test
    fun missingProto_isLegacyProtocol() {
        val qr = parseCloudQr(
            """{"app":"mnbt","type":"zjmf","url":"https://idc.example.com","code":"A1B2C3D4E5"}""",
        )
        assertTrue(qr is CloudQr.LegacyProtocol)
    }

    @Test
    fun protoBelow2_isLegacyProtocol() {
        val qr = parseCloudQr(
            """{"app":"mnbt","type":"zjmf","proto":1,"url":"https://idc.example.com","code":"A1B2C3D4E5"}""",
        )
        assertTrue(qr is CloudQr.LegacyProtocol)
    }

    @Test
    fun nonMnbtApp_isInvalid() {
        val qr = parseCloudQr(
            """{"app":"other","type":"zjmf","proto":2,"url":"https://idc.example.com","code":"A1"}""",
        )
        assertTrue(qr is CloudQr.Invalid)
    }

    @Test
    fun emptyCodeOrUrl_isInvalid() {
        assertTrue(parseCloudQr("""{"app":"mnbt","proto":2,"url":"https://a.com","code":""}""") is CloudQr.Invalid)
        assertTrue(parseCloudQr("""{"app":"mnbt","proto":2,"url":"","code":"C"}""") is CloudQr.Invalid)
    }

    @Test
    fun nonJsonOrPlainText_isInvalid() {
        assertTrue(parseCloudQr("not json at all") is CloudQr.Invalid)
        // MNBT 主机二维码是 URL 形态，不属于云站点码
        assertTrue(parseCloudQr("https://panel.example.com/app/v1/pair_page?c=ABCD2345") is CloudQr.Invalid)
    }
}
