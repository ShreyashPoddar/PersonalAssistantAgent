package com.paa.assistant.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.CommandSource
import com.paa.assistant.core.agent.Executor
import com.paa.assistant.core.sos.SosContact
import com.paa.assistant.core.sos.SosSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SosViewModel @Inject constructor(private val executor: Executor) : ViewModel() {
    fun trigger(drill: Boolean, done: (String) -> Unit) = viewModelScope.launch {
        done(executor.submit(Action.Sos(if (drill) "drill" else "button"), CommandSource.OWNER_UI).message)
    }
}

/** SOS (P1-T9): big red button, drill, and setup. */
@Composable
fun SosCard(surfaceColor: Color, textPrimary: Color, textSecondary: Color, vm: SosViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s = remember { SosSettings(context) }
    var editing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val askPerms = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> if (res.values.any { !it }) status = "Some permissions were refused — SOS needs SMS, location and phone to work fully." }
    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🆘 SOS", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (s.contacts.isEmpty()) "Not set up yet — add the people to alert."
                else "Alerts ${s.contacts.joinToString { it.name }} with your live location, then calls ${s.callNumber() ?: "nobody"}. " +
                    "Voice: \"SOS\" / \"bachao\". ${s.countdownSeconds} s to cancel.",
                color = textSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.trigger(false) { status = it } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) {
                    Text("SOS", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { vm.trigger(true) { status = it } }) { Text("Run a drill", fontSize = 12.sp) }
                TextButton(onClick = { editing = true }) { Text("Set up", fontSize = 12.sp) }
            }
            if (status.isNotBlank()) Text(status, color = textSecondary, fontSize = 11.sp)
            Text("Also set up your phone's own Emergency SOS (Settings → Safety & emergency) as a backup.", color = textSecondary, fontSize = 10.sp)
        }
    }
    if (editing) {
        var contacts by remember { mutableStateOf(s.contacts.joinToString("\n") { "${it.name}: ${it.number}" }) }
        var call by remember { mutableStateOf(s.callTarget) }
        var own by remember { mutableStateOf(s.ownNumber) }
        var siren by remember { mutableStateOf(s.siren) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("SOS setup") },
            text = {
                Column {
                    OutlinedTextField(contacts, { contacts = it }, label = { Text("People to alert (one per line: Name: number)") }, minLines = 3)
                    OutlinedTextField(call, { call = it }, label = { Text("Call (112, or blank = first person)") }, singleLine = true)
                    OutlinedTextField(own, { own = it }, label = { Text("Your own number (drills go only here)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Loud siren", modifier = Modifier.weight(1f)); Switch(siren, { siren = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    s.contacts = contacts.lines().mapNotNull { l ->
                        val name = l.substringBefore(":").trim(); val num = l.substringAfter(":", "").filter { it.isDigit() || it == '+' }
                        if (name.isNotEmpty() && num.length >= 3) SosContact(name, num) else null
                    }
                    s.callTarget = call.trim(); s.ownNumber = own.trim(); s.siren = siren
                    editing = false
                    askPerms.launch(arrayOf(
                        android.Manifest.permission.SEND_SMS, android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.CALL_PHONE
                    ))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
