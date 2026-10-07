package com.hermesandroid.relay.network.upstream

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GptLiveVoiceClientTest {
    @Test
    fun subscriptionSelectionDoesNotRequireHostVoiceModeOrApiKey() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"mode":"gpt-live","available":true,"auth_mode":"subscription"}"""))
            val client = StandardGptLiveVoiceClient(mockk<Context>(), { OkHttpClient() }, { server.url("/").toString() }, { "coder" })
            val status = client.status(requireSubscription = true).getOrThrow()
            assertTrue(status.available)
            assertEquals("subscription", status.authMode)
            assertEquals("/api/plugins/hermes-relay/voice-live/status?profile=coder", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun subscriptionSelectionFailsClosedWhenPluginIsMissing() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            val client = StandardGptLiveVoiceClient(mockk<Context>(), { OkHttpClient() }, { server.url("/").toString() })
            val result = client.status(requireSubscription = true)
            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("dashboard plugin"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun vanillaVoiceKeepsUpstreamStatusWhenPluginIsAbsent() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"mode":"gpt-live","available":true}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = StandardGptLiveVoiceClient(mockk<Context>(), { OkHttpClient() }, { server.url("/").toString() })
            assertTrue(client.status().getOrThrow().available)
            assertEquals("/api/audio/voice-live/status", server.takeRequest().path)
            assertEquals("/api/plugins/hermes-relay/voice-live/status", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test
    fun subscriptionChunksPreserveEmojiAndBoundUtf8Bytes() {
        val text = "a".repeat(499) + "😀".repeat(250) + " done"
        val chunks = chunkSubscriptionText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 500 })
        assertEquals(499, chunks.first().length)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it })
    }
}
