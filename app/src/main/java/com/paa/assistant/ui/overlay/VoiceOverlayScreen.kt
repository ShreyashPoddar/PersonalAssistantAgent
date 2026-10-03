package com.paa.assistant.ui.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * VoiceOverlayScreen — the PAA voice assistant UI.
 *
 * Design:
 *  - Dark translucent background with purple/violet gradient
 *  - Animated pulsing rings around the mic button while listening
 *  - Live partial transcription text shown as user speaks
 *  - PAA response card displayed below
 *  - Fallback text input so user can type or speak
 *  - Tap anywhere on background to dismiss
 */
@Composable
fun VoiceOverlayScreen(
    viewModel: VoiceOverlayViewModel,
    onRequestMicPermission: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var typedQuery by remember { mutableStateOf("") }

    val bgGradient = Brush.radialGradient(
        colors = listOf(
            Color(0xFF1A0A3D),
            Color(0xFF0D0D1A)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // ── MAIN PANEL ─────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF1A1A2E).copy(alpha = 0.98f),
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .clickable { /* consume click, don't dismiss */ }
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── DRAG HANDLE ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(Color(0xFF475569), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(16.dp))

            // ── STATE LABEL ───────────────────────────────────────────────────
            val stateLabel = when (uiState.state) {
                AssistantState.IDLE -> if (uiState.responseText.isNotBlank()) "Reply shown below" else "Tap mic to speak"
                AssistantState.LISTENING -> "Listening..."
                AssistantState.PROCESSING -> "Thinking..."
                AssistantState.SPEAKING -> "PAA is speaking"
                AssistantState.ERROR -> "Notice"
            }
            Text(
                text = stateLabel,
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(20.dp))

            // ── ANIMATED PULSING MIC BUTTON ───────────────────────────────────
            PulsingMicButton(
                isListening = uiState.state == AssistantState.LISTENING,
                isProcessing = uiState.state == AssistantState.PROCESSING,
                onClick = {
                    if (uiState.state == AssistantState.LISTENING) viewModel.commitCurrentSpeech()
                    else viewModel.startListening()
                }
            )

            Spacer(Modifier.height(20.dp))

            // ── LIVE TRANSCRIPTION TEXT ───────────────────────────────────────
            val displayText = when {
                uiState.partialText.isNotBlank() -> uiState.partialText
                uiState.finalUserText.isNotBlank() -> uiState.finalUserText
                else -> ""
            }
            if (displayText.isNotBlank()) {
                Text(
                    text = "\"$displayText\"",
                    color = Color(0xFFE2E8F0),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── PAA RESPONSE CARD ─────────────────────────────────────────────
            if (uiState.responseText.isNotBlank()) {
                ResponseCard(text = uiState.responseText)
                Spacer(Modifier.height(12.dp))
            }

            // ── ERROR OR STATUS HINT ───────────────────────────────────────────
            if (uiState.errorMessage.isNotBlank()) {
                val isFatal = uiState.state == AssistantState.ERROR
                Text(
                    text = uiState.errorMessage,
                    color = if (isFatal) Color(0xFFEF4444) else Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                if (uiState.errorMessage.contains("permission", ignoreCase = true)) {
                    Button(
                        onClick = onRequestMicPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                    ) {
                        Text("Grant Mic Permission")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── FALLBACK TYPE BAR ─────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF262640),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = typedQuery,
                        onValueChange = { typedQuery = it },
                        placeholder = { Text("Or type command...", color = Color(0xFF94A3B8), fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color(0xFFE2E8F0),
                            unfocusedTextColor = Color(0xFFE2E8F0),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (typedQuery.isNotBlank()) {
                                viewModel.processCommand(typedQuery)
                                typedQuery = ""
                            }
                        },
                        enabled = typedQuery.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (typedQuery.isNotBlank()) Color(0xFF7C3AED) else Color(0xFF64748B)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── CLOSE BUTTON ──────────────────────────────────────────────────
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF64748B))
                Spacer(Modifier.width(4.dp))
                Text("Close", color = Color(0xFF64748B), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PulsingMicButton(
    isListening: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit
) {
    val accentColor = Color(0xFF7C3AED)
    val processingColor = Color(0xFF0EA5E9)

    // Animated pulse for listening state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = if (isListening) 0.0f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_alpha"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer pulse ring
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(pulseScale)
                    .background(accentColor.copy(alpha = ringAlpha), CircleShape)
            )
        }

        // Main mic button
        val buttonColor = when {
            isListening -> accentColor
            isProcessing -> processingColor
            else -> Color(0xFF2D2D4E)
        }
        val icon = if (isListening) Icons.Default.MicOff else Icons.Default.Mic

        Box(
            modifier = Modifier
                .size(76.dp)
                .background(buttonColor, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = if (isListening) "Stop listening" else "Start listening",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}

@Composable
private fun ResponseCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF26184C)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(text = "🤖", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = Color(0xFFE2E8F0),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}
