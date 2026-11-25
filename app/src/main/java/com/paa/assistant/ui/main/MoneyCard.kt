package com.paa.assistant.ui.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paa.assistant.core.money.Categorizer
import com.paa.assistant.core.money.Ledger
import com.paa.assistant.core.money.MoneyIntake
import com.paa.assistant.core.money.MoneyQuery
import com.paa.assistant.data.db.MoneyDao
import com.paa.assistant.data.models.LedgerEntryEntity
import com.paa.assistant.data.models.MerchantRuleEntity
import com.paa.assistant.data.models.TransactionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MoneyViewModel @Inject constructor(private val dao: MoneyDao, private val intake: MoneyIntake) : ViewModel() {
    private val month = MoneyQuery.range("this month", System.currentTimeMillis())
    val thisMonth = dao.observeRange(month.first, month.second)
    val owed = dao.observeOpenIous()
    val status = MutableStateFlow("")

    fun importSms() = viewModelScope.launch {
        status.value = "Reading bank SMS…"
        status.value = "Imported ${intake.backfill()} new transactions from bank SMS."
    }

    /** The owner's correction sticks: every payment to this payee gets this category from now on. */
    fun recategorize(t: TransactionEntity, category: String) = viewModelScope.launch {
        dao.upsertRule(MerchantRuleEntity(Categorizer.key(t.counterparty), category))
        dao.since(0).filter { Categorizer.key(it.counterparty) == Categorizer.key(t.counterparty) }.forEach { dao.setCategory(it.id, category) }
    }

    fun addIou(text: String) = viewModelScope.launch {
        val (person, paise, dir) = Ledger.parseNote(text) ?: run { status.value = "Try: \"Rahul owes me 500\" or \"I owe Riya 200\""; return@launch }
        dao.addIou(LedgerEntryEntity(person = person, amountPaise = paise, direction = dir, reason = null, sourceChat = null, createdAt = System.currentTimeMillis()))
        status.value = ""
    }

    fun settle(e: LedgerEntryEntity) = viewModelScope.launch { dao.settle(e.id, System.currentTimeMillis()) }
}

private fun rs(p: Long) = "₹" + String.format(Locale.ENGLISH, "%,d", p / 100)

/** Money (P2): this month by category, recent payments (tap to fix a category), who owes whom. All on the phone. */
@Composable
fun MoneyCard(surfaceColor: Color, accentColor: Color, textPrimary: Color, textSecondary: Color, vm: MoneyViewModel = hiltViewModel()) {
    val txns by vm.thisMonth.collectAsStateWithLifecycle(initialValue = emptyList())
    val owed by vm.owed.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by vm.status.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf<TransactionEntity?>(null) }
    var iouText by remember { mutableStateOf("") }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.READ_SMS] == true) vm.importSms() else vm.status.value = "SMS permission refused — bank SMS can't be read."
    }
    val debits = txns.filter { it.direction == "DEBIT" }
    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("💰 Money this month: ${rs(debits.sumOf { it.amountPaise })} spent", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val top = debits.groupBy { it.category ?: "Uncategorised" }.mapValues { e -> e.value.sumOf { it.amountPaise } }
                .entries.sortedByDescending { it.value }.take(4)
            if (top.isNotEmpty()) Text(top.joinToString(" · ") { "${it.key} ${rs(it.value)}" }, color = textSecondary, fontSize = 12.sp)
            if (owed.isNotEmpty()) {
                val toMe = owed.filter { it.direction == "OWED_TO_ME" }.sumOf { it.amountPaise }
                val iOwe = owed.filter { it.direction == "I_OWE" }.sumOf { it.amountPaise }
                Text("Owed to you ${rs(toMe)} · you owe ${rs(iOwe)}", color = textSecondary, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text((if (expanded) "▾ " else "▸ ") + "Details (${txns.size})", color = textSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = { askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Import bank SMS", color = accentColor, fontSize = 12.sp)
                }
            }
            if (status.isNotBlank()) Text(status, color = textSecondary, fontSize = 11.sp)
            if (expanded) {
                val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    txns.take(60).forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { fixing = t }.padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${fmt.format(Date(t.at))}  ${t.counterparty ?: t.channel}", color = textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text((if (t.direction == "CREDIT") "+" else "−") + rs(t.amountPaise) + "  " + (t.category ?: "?"), color = textSecondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Who owes whom", color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    owed.forEach { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (e.direction == "OWED_TO_ME") "${e.person} owes you ${rs(e.amountPaise)}" else "You owe ${e.person} ${rs(e.amountPaise)}",
                                color = textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.settle(e) }, contentPadding = PaddingValues(0.dp)) { Text("Settled", fontSize = 11.sp) }
                        }
                    }
                    OutlinedTextField(iouText, { iouText = it }, singleLine = true, label = { Text("Add: \"Rahul owes me 500\"") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.addIou(iouText); iouText = "" }) { Text("Add") }
                }
            }
        }
    }
    fixing?.let { t ->
        AlertDialog(
            onDismissRequest = { fixing = null },
            title = { Text("Category for ${t.counterparty ?: "this payment"}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Categorizer.CATEGORIES.forEach { c ->
                        Text(c, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { vm.recategorize(t, c); fixing = null }.padding(vertical = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fixing = null }) { Text("Cancel") } }
        )
    }
}
