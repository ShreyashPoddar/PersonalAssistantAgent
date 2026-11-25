package com.paa.assistant.ui.main

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.paa.assistant.core.shield.A11yToggle

/** The owner's switch for PAA's screen reading (turn it off while banking). */
@Composable
fun ScreenReadingCard(surfaceColor: Color, accentColor: Color, textPrimary: Color, textSecondary: Color) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val on = remember(tick) { A11yToggle.isEnabled(context) }
    val canToggle = remember(tick) { A11yToggle.canToggle(context) }
    val openSettings = {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Surface(shape = RoundedCornerShape(16.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("👁️ Screen reading ${if (on) "on" else "off"}", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Switch(checked = on, onCheckedChange = { want ->
                    if (A11yToggle.setEnabled(context, want)) tick++ else openSettings()
                })
            }
            Text(
                "Turn this off while you use banking or UPI apps. Banks don't allow assistant apps to read their " +
                    "screens, and PAA respects that. For the least friction, keep your banking apps in a separate " +
                    "profile (see the guide) where PAA isn't enabled at all.",
                color = textSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (canToggle) "One-tap switch allowed. Also in Quick Settings (\"PAA screen reading\") and by voice (\"banking mode\")."
                else "The switch opens Accessibility settings (one-tap switching not granted).",
                color = textSecondary, fontSize = 11.sp
            )
            TextButton(onClick = openSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Open Accessibility settings", color = accentColor, fontSize = 12.sp)
            }
            var guide by remember { mutableStateOf(false) }
            TextButton(onClick = { guide = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("How to keep banking separate", color = accentColor, fontSize = 12.sp)
            }
            if (guide) androidx.compose.material3.AlertDialog(
                onDismissRequest = { guide = false },
                title = { Text("Banking and PAA") },
                text = {
                    Text(
                        "Recommended: keep banking/UPI apps in a second user profile (Settings → Users → Add user) where PAA " +
                            "isn't installed, and switch to it to bank. Accessibility is per profile, so PAA never touches it.\n\n" +
                            "Or: before banking, tap the \"PAA screen reading\" tile or say \"banking mode\"; afterwards \"screen " +
                            "reading on\". PAA never watches which app is open and never hides itself.\n\n" +
                            "Developer options on → many bank apps refuse to open. UPI PINs are only ever typed by you.",
                        fontSize = 13.sp
                    )
                },
                confirmButton = { TextButton(onClick = { guide = false }) { Text("OK") } }
            )
            var remind by remember { mutableStateOf(!com.paa.assistant.core.shield.DevOptionsReminder.muted(context)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Remind me when Developer options are on (banks may refuse)", color = textSecondary, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Switch(checked = remind, onCheckedChange = { remind = it; com.paa.assistant.core.shield.DevOptionsReminder.setMuted(context, !it) })
            }
        }
    }
}
