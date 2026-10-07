package com.hermesandroid.relay.voice

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.network.shared.GptLiveCallbacks
import com.hermesandroid.relay.network.shared.GptLiveHistoryMessage
import com.hermesandroid.relay.network.shared.GptLiveTranscriptFragment
import com.hermesandroid.relay.network.shared.GptLiveSession
import com.hermesandroid.relay.network.shared.GptLiveStatus
import com.hermesandroid.relay.network.shared.GptLiveVoiceClient
import com.hermesandroid.relay.viewmodel.VoiceMessageSubmissionResult
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GptLiveSessionTest {
    private val store = ViewModelStore()
    private val recorder = mockk<VoiceRecorder>(relaxed = true) {
        every { amplitude } returns MutableStateFlow(0f)
        every { isRecording() } returns false
    }

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun coordinator(client: GptLiveVoiceClient, preferences: VoicePreferencesRepository? = null, chatOverride: ChatViewModel? = null): VoiceViewModel {
        val chat = chatOverride ?: mockk<ChatViewModel>(relaxed = true) {
            every { messages } returns MutableStateFlow(emptyList<ChatMessage>())
        }
        val player = mockk<VoicePlayer>(relaxed = true) {
            every { amplitude } returns MutableStateFlow(0f)
        }
        return VoiceViewModel(mockk<Application>(relaxed = true)).also {
            store.put("voice", it)
            it.initialize(voiceClient = mockk(relaxed = true), gptLiveVoiceClient = client,
                chatViewModel = chat, recorder = recorder, player = player, sfxPlayer = mockk(relaxed = true),
                voicePreferences = preferences)
            it.setVoiceEngineModeForTest(VoiceEngineMode.GptLive)
        }
    }

    @Test fun pausedMicrophoneResumesWithoutOpeningStandardRecorder() = runTest {
        val client = FakeLiveClient()
        val voice = coordinator(client)
        voice.enterVoiceMode()
        assertTrue(voice.voiceStats.value.gptLiveActive)
        voice.pauseContinuousMode()
        assertTrue(voice.uiState.value.gptLiveMuted)
        assertEquals(VoiceState.Idle, voice.uiState.value.state)
        voice.startListening()
        assertFalse(voice.uiState.value.gptLiveMuted)
        assertEquals(VoiceState.Listening, voice.uiState.value.state)
        verify { client.session.setMuted(true); client.session.setMuted(false) }
        verify(exactly = 0) { recorder.startRecording() }
        voice.exitVoiceMode()
        verify { client.session.close() }
    }

    @Test fun switchingEngineRetiresCaptureAndClearsLiveSpeakingState() = runTest {
        val settings = MutableStateFlow(VoiceSettings(engineMode = VoiceEngineMode.GptLive.storageValue))
        val preferences = mockk<VoicePreferencesRepository>(relaxed = true) {
            every { this@mockk.settings } returns settings
        }
        val client = FakeLiveClient()
        val voice = coordinator(client, preferences)
        voice.enterVoiceMode()
        client.callbacks!!.onSpeakingChanged(true)
        assertEquals(VoiceState.Speaking, voice.uiState.value.state)
        settings.value = settings.value.copy(engineMode = VoiceEngineMode.RealtimeAgent.storageValue)
        assertFalse(voice.voiceStats.value.gptLiveActive)
        assertEquals(VoiceState.Idle, voice.uiState.value.state)
        assertFalse(voice.uiState.value.outputAudioActive)
        verify { client.session.close() }
        verify(exactly = 0) { recorder.startRecording() }
    }

    @Test fun delegatedAnswerAppearsOnceAsProviderCaptionAndResetsForNextTurn() = runTest {
        for (streamingFirst in listOf(false, true)) {
            val streaming = MutableStateFlow(streamingFirst)
            val messages = MutableStateFlow(emptyList<ChatMessage>())
            val chat = mockk<ChatViewModel>(relaxed = true) {
                every { this@mockk.messages } returns messages
                every { isStreaming } returns streaming
                every { sendGptLiveDelegation(any(), any(), any(), any()) } answers {
                    messages.value = listOf(ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "Hello. there", timestamp = 0L))
                    VoiceMessageSubmissionResult.Submitted("user")
                }
            }
            val client = FakeLiveClient()
            val voice = coordinator(client, chatOverride = chat)
            voice.enterVoiceMode()
            val callbacks = client.callbacks!!
            callbacks.onDelegation("delegation", "Say hello", emptyList())
            verify { client.session.speak("delegation", if (streamingFirst) "Hello." else "Hello. there") }
            callbacks.onSpeakingChanged(true)
            callbacks.onTranscript(GptLiveTranscriptFragment(GptLiveTranscriptFragment.Speaker.Assistant, "Hello. ", 0L, 1L))
            streaming.value = false
            advanceTimeBy(201)
            runCurrent()
            callbacks.onSpeakingChanged(true)
            callbacks.onTranscript(GptLiveTranscriptFragment(GptLiveTranscriptFragment.Speaker.Assistant, "there", 1L, 2L))
            assertEquals("Hello. there", voice.uiState.value.responseText)
            assertEquals(VoiceState.Speaking, voice.uiState.value.state)
            callbacks.onSpeakingChanged(false)
            callbacks.onSpeakingChanged(true)
            callbacks.onTranscript(GptLiveTranscriptFragment(GptLiveTranscriptFragment.Speaker.Assistant, "Next answer", 2L, 3L))
            assertEquals("Next answer", voice.uiState.value.responseText)
        }
    }

    @Test fun missingSubscriptionFailsClosedWithVisibleError() = runTest {
        val client = FakeLiveClient(available = false)
        val voice = coordinator(client)
        voice.enterVoiceMode()
        assertEquals(VoiceState.Error, voice.uiState.value.state)
        assertEquals("Codex login required", voice.uiState.value.error)
        assertEquals(0, client.starts)
        voice.startListening()
        assertEquals(VoiceState.Error, voice.uiState.value.state)
        verify(exactly = 0) { recorder.startRecording() }
    }

    @Test fun staleProviderCallbacksCannotChangeNewSession() = runTest {
        val client = FakeLiveClient()
        val voice = coordinator(client)
        voice.enterVoiceMode()
        val stale = client.callbacks!!
        voice.exitVoiceMode()
        voice.enterVoiceMode()
        stale.onClosed("connection_lost", null)
        stale.onError("old failure", true)
        stale.onSpeakingChanged(true)
        assertEquals(VoiceState.Listening, voice.uiState.value.state)
        assertNull(voice.uiState.value.error)
        assertTrue(voice.voiceStats.value.gptLiveActive)
    }

    @Test fun exitDuringNegotiationCancelsStartupAndNeverPublishesLiveState() = runTest {
        val pending = CompletableDeferred<Unit>()
        val client = FakeLiveClient(pending = pending)
        val voice = coordinator(client)
        voice.enterVoiceMode()
        assertEquals(1, client.starts)
        voice.exitVoiceMode()
        pending.complete(Unit)
        assertFalse(voice.voiceStats.value.gptLiveActive)
        assertFalse(voice.uiState.value.voiceMode)
    }

    private class FakeLiveClient(
        val available: Boolean = true,
        val pending: CompletableDeferred<Unit>? = null,
    ) : GptLiveVoiceClient {
        val session = mockk<GptLiveSession>(relaxed = true)
        var callbacks: GptLiveCallbacks? = null
        var starts = 0
        override suspend fun status(requireSubscription: Boolean): Result<GptLiveStatus> {
            check(requireSubscription)
            return Result.success(GptLiveStatus("gpt-live", available, reason = "Codex login required"))
        }
        override suspend fun startSession(history: List<GptLiveHistoryMessage>, callbacks: GptLiveCallbacks,
            requireSubscription: Boolean): Result<GptLiveSession> {
            check(requireSubscription)
            starts++
            this.callbacks = callbacks
            pending?.await()
            return Result.success(session)
        }
    }
}
