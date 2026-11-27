package com.paa.assistant.ui.main

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paa.assistant.core.hub.HubClient
import com.paa.assistant.core.hub.HubPairing
import com.paa.assistant.core.hub.HubSettings
import kotlinx.coroutines.launch

/** Hub (P4-T1): pair with the owner's laptop hub and check it's reachable. Calls features come later. */
@Composable
fun HubCard(surfaceColor: Color, accentColor: Color, textPrimary: Color, textSecondary: Color) {
    val context = LocalContext.current
    val settings = remember { HubSettings(context) }
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(settings.pairing) }
    var payload by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🖥️ Hub (laptop)", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                paired?.let { "Paired with ${it.host}:${it.port}. When it's reachable, chats are read by the laptop's bigger AI " +
                    "and WhatsApp Desktop's chat list is checked; otherwise the phone's own AI is used." }
                    ?: "Run hub\\start_laptop.ps1 on your laptop (Tailscale on) and paste the pairing line it prints.",
                color = textSecondary, fontSize = 12.sp
            )
            if (paired != null) {
                var on by remember { mutableStateOf(settings.enabled) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Use the laptop", color = textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; settings.enabled = it })
                }
            }
            if (paired == null) {
                OutlinedTextField(payload, { payload = it }, label = { Text("Pairing line") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paired == null) TextButton(onClick = {
                    val p = HubPairing.parse(payload)
                    if (p == null) { status = "That doesn't look like the hub's pairing line."; return@TextButton }
                    scope.launch {
                        HubClient(p).pair(Build.MODEL).onSuccess { settings.pairing = p; settings.enabled = true; paired = p; status = "Paired." }
                            .onFailure { status = "Couldn't reach the hub: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }, contentPadding = PaddingValues(0.dp)) { Text("Pair", color = accentColor, fontSize = 12.sp) }
                else {
                    TextButton(onClick = {
                        scope.launch { status = HubClient(paired!!).health().getOrElse { "Hub not reachable: ${it.message ?: it.javaClass.simpleName}" } }
                    }, contentPadding = PaddingValues(0.dp)) { Text("Check", color = accentColor, fontSize = 12.sp) }
                    TextButton(onClick = { settings.pairing = null; settings.enabled = false; paired = null; status = "Unpaired." },
                        contentPadding = PaddingValues(0.dp)) { Text("Unpair", fontSize = 12.sp) }
                }
            }
            if (status.isNotBlank()) Text(status, color = textSecondary, fontSize = 11.sp)
        }
    }
}
