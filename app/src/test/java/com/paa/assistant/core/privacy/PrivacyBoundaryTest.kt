package com.paa.assistant.core.privacy

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Chat messages and call transcripts must never reach a cloud service. The files that handle them
 * may not even mention a cloud client — so a future edit can't wire one in by accident.
 */
class PrivacyBoundaryTest {
    private val src = File("src/main/java/com/paa/assistant").let { if (it.exists()) it else File("app/src/main/java/com/paa/assistant") }
    private val privateFiles = listOf(
        "services/ChatMessageProcessor.kt", "services/NotificationListener.kt", "services/ChatTaskAccessibilityService.kt",
        "services/CallRecordingProcessor.kt", "services/WakeListenerService.kt", "services/ChatTaskScheduler.kt",
        "core/ai/LocalLlm.kt"
    )
    private val cloud = Regex("""GeminiClient|ClaudeClient|TavilySearch|api\.anthropic|generativelanguage|api\.tavily|HttpURLConnection|OkHttp|Retrofit""")

    @Test fun `private pipelines never touch cloud clients`() {
        for (f in privateFiles) {
            val text = File(src, f).readText()
            assertFalse("$f mentions a cloud client: ${cloud.find(text)?.value}", cloud.containsMatchIn(text))
        }
    }
}
