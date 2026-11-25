package com.paa.assistant.core.privacy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-T9: bank data never reaches anything that talks to the cloud, and the debug log never holds amounts or payees.
 * Source-level gate (like `grep -rn "amountPaise\|counterparty" core/ai/` returning nothing).
 */
class MoneyPrivacyTest {
    private val src = listOf(File("src/main/java/com/paa/assistant"), File("app/src/main/java/com/paa/assistant")).first { it.exists() }
    private fun kt(dir: String) = File(src, dir).walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun cloudCodeNeverTouchesMoney() {
        val money = Regex("\\b(amountPaise|counterparty|balancePaise|MoneyDao|TransactionEntity|BankSmsParser|SmsInbox|LedgerEntryEntity)\\b")
        val offenders = kt("core/ai").filter { money.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("cloud/AI code references money data: $offenders", offenders.isEmpty())
    }

    @Test fun moneyLogLinesCarryNoAmountsOrNames() {
        val bad = kt("core/money").flatMap { f ->
            f.readLines().filter { "DetectionLog.add" in it && Regex("amount|counterparty|body|text|person|\\\$\\{?t\\.").containsMatchIn(it.substringAfter("DetectionLog.add")) }
                .map { "${f.name}: ${it.trim()}" }
        }
        assertTrue("money code logs private details: $bad", bad.isEmpty())
    }

    @Test fun moneyAnswersStayOutOfCloudHistory() {
        val vm = File(src, "ui/overlay/VoiceOverlayViewModel.kt").readText()
        val block = vm.substringAfter("isMoneyQuestion(input)").take(400)
        assertTrue("money answers must set lastInputPrivate", "lastInputPrivate = true" in block)
    }
}
