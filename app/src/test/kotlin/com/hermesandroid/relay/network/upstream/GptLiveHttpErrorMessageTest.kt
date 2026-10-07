package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class GptLiveHttpErrorMessageTest {

    private val op = "Relay GPT-Live session creation"

    @Test fun gatewayErrorPageIsNotShown() {
        val proxyBody = """{"title":"Error 502: Bad gateway","error_name":"origin_bad_gateway","ray_id":"x"}"""
        assertEquals(
            "$op failed: Hermes server unavailable (HTTP 502)",
            gptLiveHttpErrorMessage(op, 502, proxyBody),
        )
    }

    @Test fun shortServerDetailIsShown() {
        assertEquals(
            "$op failed (HTTP 400): GPT-Live is not selected",
            gptLiveHttpErrorMessage(op, 400, """{"detail":"GPT-Live is not selected"}"""),
        )
    }

    @Test fun usageLimitDetailPassesThrough() {
        assertEquals(
            "$op failed (HTTP 429): ChatGPT usage limit reached",
            gptLiveHttpErrorMessage(op, 429, """{"detail":"ChatGPT usage limit reached"}"""),
        )
        assertEquals(
            "$op failed: Hermes server unavailable (HTTP 503)",
            gptLiveHttpErrorMessage(op, 503, "<html>down</html>"),
        )
    }

    @Test fun htmlOrLongBodyFallsBackToStatusOnly() {
        assertEquals("$op failed (HTTP 500)", gptLiveHttpErrorMessage(op, 500, "<html>oops</html>"))
        val long = """{"detail":"${"x".repeat(300)}"}"""
        assertEquals("$op failed (HTTP 500)", gptLiveHttpErrorMessage(op, 500, long))
    }

    @Test fun authAndMissingRouteKeepTheirMessages() {
        assertEquals("$op needs dashboard sign-in", gptLiveHttpErrorMessage(op, 401, ""))
        assertEquals("$op is unavailable on this Hermes build", gptLiveHttpErrorMessage(op, 404, ""))
    }
}
