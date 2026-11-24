package com.paa.assistant.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paa.assistant.core.reminders.AlarmSwitch
import com.paa.assistant.core.reminders.ReminderScheduler
import kotlinx.coroutines.launch

/** The owner's "All alarms off" switch (stays off until turned back on). */
@Composable
fun AlarmsCard(surfaceColor: Color, textPrimary: Color, textSecondary: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var off by remember { mutableStateOf(ReminderScheduler.alarmsOff(context)) }
    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (off) "🔕 All alarms off" else "🔔 Alarms on", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Switch(checked = !off, onCheckedChange = { on ->
                    off = !on
                    scope.launch { AlarmSwitch.set(context, off = !on) }
                })
            }
            Text(
                if (off) "Nothing rings or calls — task alarms, heads-ups and \"call me\" reminders are all off, and stay " +
                    "off until you turn this back on. Your tasks are kept. Voice: \"alarms on\"."
                else "Turn off to silence every PAA alarm and reminder call. Voice: \"alarms off\" / \"sab alarm band karo\".",
                color = textSecondary, fontSize = 12.sp
            )
        }
    }
}
