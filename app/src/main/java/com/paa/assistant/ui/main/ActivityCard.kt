package com.paa.assistant.ui.main

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paa.assistant.core.messaging.ContactResolver
import com.paa.assistant.data.db.AuditDao
import com.paa.assistant.data.db.ContactRuleDao
import com.paa.assistant.data.models.ContactRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor(
    audit: AuditDao,
    private val rules: ContactRuleDao
) : ViewModel() {
    val recent = audit.recent(50)
    val contactRules = rules.all()

    fun setMode(rule: ContactRuleEntity, mode: String) = viewModelScope.launch {
        rules.upsert(rule.copy(outward = mode, updatedAt = System.currentTimeMillis()))
    }

    /** A rule for a contact not messaged yet: the first message still needs a fingerprint. */
    fun add(name: String) = viewModelScope.launch {
        val key = name.trim().lowercase()
        if (key.isNotEmpty() && rules.get(key) == null) rules.upsert(ContactRuleEntity(contactKey = key))
    }
}

/** What PAA did or refused lately (from the audit log), and the per-contact rules for outward actions. */
@Composable
fun ActivityCard(
    surfaceColor: Color, accentColor: Color, textPrimary: Color, textSecondary: Color,
    vm: ActivityViewModel = hiltViewModel()
) {
    val entries by vm.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    var expanded by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🛡️ Activity", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text((if (expanded) "▾ " else "▸ ") + "Last actions (${entries.size})", color = textSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = { showRules = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("Contact rules", color = accentColor, fontSize = 12.sp)
                }
            }
            if (expanded) {
                if (entries.isEmpty()) Text("Nothing yet.", color = textSecondary, fontSize = 11.sp)
                entries.forEach { e ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text("${fmt.format(Date(e.at))}  ${if (e.ok) "✅" else "❌"}  ${e.verdict}", color = textSecondary, fontSize = 10.sp)
                        Text(e.summary, color = textPrimary, fontSize = 11.sp)
                        if (!e.ok) Text(e.resultMessage, color = accentColor, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (showRules) ContactRulesDialog(vm, textSecondary) { showRules = false }
}

private val MODES = listOf("AUTO", "PREVIEW", "ALWAYS_ASK")

@Composable
private fun ContactRulesDialog(vm: ActivityViewModel, textSecondary: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rules by vm.contactRules.collectAsStateWithLifecycle(initialValue = emptyList())
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contact rules") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "AUTO sends without asking · PREVIEW shows the message first · ALWAYS_ASK needs a fingerprint. " +
                        "The first message to anyone always needs a fingerprint. Tap a mode to change it.",
                    color = textSecondary, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                if (rules.isEmpty()) Text("No contacts yet.", fontSize = 12.sp)
                rules.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.contactKey, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            r.outward + if (r.autoReplyDuringCall) " · call auto-reply" else "",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { vm.setMode(r, MODES[(MODES.indexOf(r.outward) + 1) % MODES.size]) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text("Add contact") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Use the saved contact's name when there is one, so the rule matches what the overlay sends to.
                val name = ContactResolver.find(context, newName).firstOrNull()?.name ?: newName
                vm.add(name)
                newName = ""
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
