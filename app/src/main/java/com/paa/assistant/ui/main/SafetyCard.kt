package com.paa.assistant.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.paa.assistant.core.agent.AgentSettings
import com.paa.assistant.core.agent.Lockdown

/** Kill switch + Lockdown (P1-T12), at the top of the main screen. */
@Composable
fun SafetyCard(surfaceColor: Color, textPrimary: Color, textSecondary: Color) {
    val context = LocalContext.current
    val settings = remember { AgentSettings(context) }
    var paused by remember { mutableStateOf(settings.paused) }
    var lockdown by remember { mutableStateOf(settings.lockdown) }
    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🛑 Safety", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Pause PAA", color = textPrimary, fontSize = 13.sp)
                    Text("Nothing is done for you (no messages, calls, opening apps). SOS still works.", color = textSecondary, fontSize = 11.sp)
                }
                Switch(checked = paused, onCheckedChange = { settings.paused = it; paused = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Lockdown", color = textPrimary, fontSize = 13.sp)
                    Text("Only alarms, reminders and SOS. Also turns off screen reading and \"Oyee PA\"; turning it off " +
                        "brings back what was on. Also a Quick Settings tile \"PAA Lockdown\".", color = textSecondary, fontSize = 11.sp)
                }
                Switch(checked = lockdown, onCheckedChange = { Lockdown.set(context, it); lockdown = it })
            }
        }
    }
}
