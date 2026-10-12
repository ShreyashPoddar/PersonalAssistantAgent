package com.paa.assistant.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * The fine-tuned model is trained on train/paa_format.py's prompt; the phone must send byte-identical text.
 * Same cases as GOLDEN_CASES there. After changing either side: python train/paa_format.py --golden
 */
class CompactPromptTest {
    private fun ctx(chat: String? = null, sender: String? = null, group: Boolean = false, outgoing: Boolean = false,
                    recent: List<String> = emptyList(), learned: List<Pair<String, String?>> = emptyList(),
                    call: Boolean = false, open: List<String> = emptyList(), burst: Boolean = false) =
        LocalLlm.ChatContext(outgoing, chat, sender, group, recent, learned, call, open, burst)

    @Test
    fun matchesTrainingFormat() {  // UserProfile defaults: shreyash/shreyas, 2028 (as in paa_format GOLDEN)
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 12, 21, 5) }.time
        val cases = listOf(
            "@shreyash bring the projector at 1145" to ctx(chat = "Project Group", sender = "Rohit", group = true,
                recent = listOf("Aman: ppt done?", "Rohit: \"almost\"\nfinal")),
            "You: haan\nZoya: parso tak\nZoya: 11 baje se pehle" to ctx(chat = "Zoya", burst = true, open = listOf("Send essay to Zoya (kal)")),
            "kal bhej dunga" to ctx(chat = "Papa", outgoing = true, learned = listOf("ok" to null, "send it" to "Send the file")),
            "so tum kal tak form bhar dena" to ctx(call = true),
            "hi" to ctx(),
        )
        val ours = cases.joinToString("\n=====\n") { (m, c) -> LocalLlm.buildCompactPrompt(m, c, now) }
        val golden = File("../train/golden_prompt.txt").readText(Charsets.UTF_8)
        assertEquals(golden, ours)
    }
}
