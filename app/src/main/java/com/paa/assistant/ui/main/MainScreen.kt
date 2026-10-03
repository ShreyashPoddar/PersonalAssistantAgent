package com.paa.assistant.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.paa.assistant.services.FloatingPillService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.models.MemoryFactEntity
import com.paa.assistant.data.models.FileIndexEntity
import com.paa.assistant.ui.overlay.VoiceOverlayActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val tasks by viewModel.pendingTasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val memoryFacts by viewModel.memoryFacts.collectAsStateWithLifecycle(initialValue = emptyList())
    val indexedFiles by viewModel.indexedFiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val aiResponse by viewModel.aiResponse.collectAsStateWithLifecycle()
    val localAiInstalled by viewModel.localAiInstalled.collectAsStateWithLifecycle()
    val importingModel by viewModel.importingModel.collectAsStateWithLifecycle()
    val chatChecks by com.paa.assistant.services.DetectionLog.entries.collectAsStateWithLifecycle()
    var showChatChecks by remember { mutableStateOf(false) }
    var confirmClearChatTasks by remember { mutableStateOf(false) }
    val wakeCtx = LocalContext.current
    var wakeListenerOn by remember { mutableStateOf(com.paa.assistant.services.WakeListenerService.isEnabled(wakeCtx)) }


    val context = LocalContext.current
    // Re-check permissions every time the user comes back to the app (e.g. from Settings)
    var permissionTick by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        permissionTick++
        onPauseOrDispose { }
    }
    val notificationAccessOn = remember(permissionTick) {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val audioPerm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var recordingsOn by remember(permissionTick) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPerm) == PackageManager.PERMISSION_GRANTED)
    }
    val recordingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recordingsOn = it }
    var contactsOn by remember(permissionTick) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOn = it }
    val chatReadingOn = remember(permissionTick) {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.let { it.contains(context.packageName) && it.contains("ChatTaskAccessibilityService") } == true
    }
    var inputText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showIndexedFilesDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var isFloatingPillActive by remember { mutableStateOf(FloatingPillService.isRunning) }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore
            }
            viewModel.indexDirectory(it)
        }
    }

    // Pick the Gemma .task file from phone storage (e.g. Downloads) to install the on-device AI
    val modelFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it) } }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            context.startActivity(Intent(context, VoiceOverlayActivity::class.java))
        }
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        hasLocationPermission = fineGranted || coarseGranted
    }

    fun launchVoiceAssistant() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, VoiceOverlayActivity::class.java))
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val bgColor = Color(0xFF0D0D1A)
    val surfaceColor = Color(0xFF1A1A2E)
    val accentColor = Color(0xFF7C3AED)
    val textPrimary = Color(0xFFE2E8F0)
    val textSecondary = Color(0xFF94A3B8)

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "PAA",
                            color = accentColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Personal Assistant Agent • Gemini Flash",
                            color = textSecondary,
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor),
                actions = {
                    IconButton(onClick = { editingTask = TaskEntity(title = "") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Task", tint = accentColor)
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = textSecondary)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { launchVoiceAssistant() },
                containerColor = accentColor,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 72.dp)
                    .size(60.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Voice assistant",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        bottomBar = {
            // ── TEXT INPUT PROMPT BAR ─────────────────────────────────────────
            Surface(
                color = surfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask PAA or type a task...", color = textSecondary, fontSize = 14.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF25253E), RoundedCornerShape(24.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    viewModel.processCommand(inputText)
                                    inputText = ""
                                }
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.processCommand(inputText)
                                inputText = ""
                            }
                        },
                        enabled = !isProcessing && inputText.isNotBlank(),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = accentColor,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF33334E),
                            disabledContentColor = Color(0xFF666680)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── AI RESPONSE BANNER ────────────────────────────────────────────
            if (aiResponse != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF26184C),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("🤖", fontSize = 20.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("PAA Assistant", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text(aiResponse ?: "", color = textPrimary, fontSize = 14.sp)
                            }
                            IconButton(
                                onClick = { viewModel.clearAiResponse() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // ── ON-DEVICE AI SETUP ────────────────────────────────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (localAiInstalled) Color(0xFF12291F) else Color(0xFF3A2410),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            if (localAiInstalled) "🧠 Local AI installed — chats are understood on this phone"
                            else "🧠 Local AI not installed (or the file is invalid) — incoming messages are skipped",
                            color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        if (!localAiInstalled) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap Install and pick gemma-3n-E2B-it-int4.task, or the Kaggle .tar.gz download as-is — PAA unpacks it.",
                                color = textSecondary, fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!localAiInstalled) {
                                Button(
                                    onClick = { modelFileLauncher.launch(arrayOf("*/*")) },
                                    enabled = !importingModel
                                ) { Text(if (importingModel) "Installing…" else "Install local AI", fontSize = 12.sp) }
                            }

                        }

                        // "Oyee PA" always-on offline listener (also works during calls via the accessibility service)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🗣️ \"Oyee PA\" listener", color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Say it anytime to open PAA, or during a call to schedule what was just agreed. Offline; uses some battery.",
                                    color = textSecondary, fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = wakeListenerOn,
                                onCheckedChange = { on ->
                                    wakeListenerOn = on
                                    com.paa.assistant.services.WakeListenerService.setEnabled(context, on)
                                    if (on) com.paa.assistant.services.WakeListenerService.startIfEnabled(context)
                                    else com.paa.assistant.services.WakeListenerService.stop(context)
                                }
                            )
                        }

                        // Offline speech ships inside the APK (Vosk English-India) — nothing to download
                        Spacer(Modifier.height(8.dp))
                        Text("🎤 Offline speech: built in (English–India), no download needed", color = textSecondary, fontSize = 12.sp)

                        // What chat detection needs, and whether it's on
                        Spacer(Modifier.height(10.dp))
                        PermissionLine(
                            ok = notificationAccessOn,
                            label = "Notification access (incoming messages)",
                            onFix = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        )
                        PermissionLine(
                            ok = chatReadingOn,
                            label = "Accessibility (your sent messages + open chat)",
                            onFix = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        )
                        PermissionLine(
                            ok = contactsOn,
                            label = "Contacts (send WhatsApp messages you approve)",
                            onFix = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
                        )
                        PermissionLine(
                            ok = recordingsOn,
                            label = "Music and audio (tasks from call recordings)",
                            onFix = { recordingsLauncher.launch(audioPerm) }
                        )

                        if (localAiInstalled) {
                            TextButton(onClick = { viewModel.testLocalAi() }, contentPadding = PaddingValues(0.dp)) {
                                Text("🔬 Test local AI", color = textSecondary, fontSize = 12.sp)
                            }
                            // Speed of the last on-device AI run (prompt size and time), to measure improvements
                            Text("⚙️ Last AI run: ${viewModel.aiStats()} · ${viewModel.memoryMb()} MB used", color = textSecondary, fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = { com.paa.assistant.services.CallAudioProbeService.start(context) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📞 Test call audio (then make a call)", color = textSecondary, fontSize = 12.sp)
                        }
                        // About the owner: names, batch, groups, alarm window (used by chat detection on the phone)
                        var editMe by remember { mutableStateOf(false) }
                        TextButton(onClick = { editMe = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("⚙️ About me (names, batch, groups, alarm hours)", color = textSecondary, fontSize = 12.sp)
                        }
                        if (editMe) {
                            val up = com.paa.assistant.core.profile.UserProfile
                            var names by remember { mutableStateOf(up.names.joinToString(", ")) }
                            var year by remember { mutableStateOf(up.GRADUATION_YEAR.toString()) }
                            var groups by remember { mutableStateOf(up.hackathonGroups.joinToString(", ")) }
                            var start by remember { mutableStateOf(up.ALARM_START_HOUR.toString()) }
                            var end by remember { mutableStateOf(up.ALARM_END_HOUR.toString()) }
                            var groupMin by remember { mutableStateOf(up.GROUP_REQUEST_DEFAULT_MINUTES.toString()) }
                            var autoReply by remember { mutableStateOf(up.autoReplyContacts.joinToString(", ")) }
                            AlertDialog(
                                onDismissRequest = { editMe = false },
                                title = { Text("About me") },
                                text = {
                                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                        OutlinedTextField(names, { names = it }, label = { Text("Names people call me (comma-separated)") })
                                        OutlinedTextField(year, { year = it }, label = { Text("Graduation year") }, singleLine = true)
                                        OutlinedTextField(groups, { groups = it }, label = { Text("Groups where hackathon talk is for me") })
                                        OutlinedTextField(start, { start = it }, label = { Text("Alarms may ring from (hour, 0–23)") }, singleLine = true)
                                        OutlinedTextField(end, { end = it }, label = { Text("…until (hour, 1–24)") }, singleLine = true)
                                        OutlinedTextField(groupMin, { groupMin = it }, label = { Text("Group request without a time: due in (minutes)") }, singleLine = true)
                                        OutlinedTextField(autoReply, { autoReply = it }, label = { Text("Auto-reply \"on a call\" to (chat names, comma-separated; empty = off)") })
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        up.save(context, names, year.toIntOrNull() ?: up.GRADUATION_YEAR, groups,
                                            start.toIntOrNull() ?: up.ALARM_START_HOUR, end.toIntOrNull() ?: up.ALARM_END_HOUR,
                                            groupMin.toIntOrNull() ?: up.GROUP_REQUEST_DEFAULT_MINUTES, autoReply)
                                        editMe = false
                                    }) { Text("Save") }
                                },
                                dismissButton = { TextButton(onClick = { editMe = false }) { Text("Cancel") } }
                            )
                        }

                        // Details for hackathon registration forms (encrypted, filled only into forms you open)
                        var editProfile by remember { mutableStateOf(false) }
                        TextButton(onClick = { editProfile = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("🧾 Registration profile (for hackathon forms)", color = textSecondary, fontSize = 12.sp)
                        }
                        if (editProfile) {
                            val values = remember {
                                androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
                                    putAll(com.paa.assistant.core.hackathon.RegistrationProfile.load(context))
                                }
                            }
                            AlertDialog(
                                onDismissRequest = { editProfile = false },
                                title = { Text("Registration profile") },
                                text = {
                                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                        Text("Stored encrypted on this phone. PAA fills these into forms you open and check yourself.", fontSize = 11.sp)
                                        com.paa.assistant.core.hackathon.RegistrationProfile.FIELDS.forEach { (key, label) ->
                                            OutlinedTextField(value = values[key] ?: "", onValueChange = { values[key] = it },
                                                label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        com.paa.assistant.core.hackathon.RegistrationProfile.save(context, values.toMap())
                                        editProfile = false
                                    }) { Text("Save") }
                                },
                                dismissButton = { TextButton(onClick = { editProfile = false }) { Text("Cancel") } }
                            )
                        }

                        // Voice lock: "Oyee PA" only for the owner's voice
                        var lockOn by remember { mutableStateOf(viewModel.voiceLockOn()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔐 Only my voice", color = textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.startEnrolment() }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (lockOn) "Re-enrol" else "Enrol my voice", fontSize = 12.sp)
                            }
                            if (lockOn) Switch(checked = true, onCheckedChange = { viewModel.setVoiceLock(false); lockOn = false })
                        }
                        val enrolStep by viewModel.enrolStep.collectAsStateWithLifecycle()
                        val enrolStatus by viewModel.enrolStatus.collectAsStateWithLifecycle()
                        enrolStep?.let { step ->
                            AlertDialog(
                                onDismissRequest = { viewModel.cancelEnrolment(); lockOn = viewModel.voiceLockOn() },
                                title = { Text("Teach PAA your voice (${minOf(step + 1, viewModel.enrolPhrases.size)}/${viewModel.enrolPhrases.size})") },
                                text = {
                                    Column {
                                        Text(enrolStatus, fontSize = 13.sp)
                                        Spacer(Modifier.height(10.dp))
                                        Text("\"${viewModel.enrolPhrases.getOrElse(step) { "" }}\"", fontSize = 18.sp)
                                        Spacer(Modifier.height(6.dp))
                                        Text("Only a voiceprint is kept, encrypted — never the recording.", fontSize = 11.sp)
                                    }
                                },
                                confirmButton = { TextButton(onClick = { viewModel.recordEnrolPhrase() }) { Text("🎙️ Record (4 s)") } },
                                dismissButton = { TextButton(onClick = { viewModel.cancelEnrolment() }) { Text("Cancel") } }
                            )
                        }
                        LaunchedEffect(enrolStep) { if (enrolStep == null) lockOn = viewModel.voiceLockOn() }
                        TextButton(onClick = { confirmClearChatTasks = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("🧹 Remove all tasks auto-detected from chats", color = textSecondary, fontSize = 12.sp)
                        }
                        if (confirmClearChatTasks) {
                            AlertDialog(
                                onDismissRequest = { confirmClearChatTasks = false },
                                title = { Text("Remove chat tasks?") },
                                text = { Text("Deletes every pending task PAA created from WhatsApp/Telegram messages. Tasks you added yourself stay.") },
                                confirmButton = {
                                    TextButton(onClick = { viewModel.clearChatTasks(); confirmClearChatTasks = false }) { Text("Remove") }
                                },
                                dismissButton = { TextButton(onClick = { confirmClearChatTasks = false }) { Text("Cancel") } }
                            )
                        }

                        // Why each recent message was / wasn't scheduled
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { showChatChecks = !showChatChecks }, contentPadding = PaddingValues(0.dp)) {
                            Text(
                                (if (showChatChecks) "▾ " else "▸ ") + "Recent chat checks (${chatChecks.size})",
                                color = textSecondary, fontSize = 12.sp
                            )
                        }
                        if (showChatChecks) {
                            if (chatChecks.isEmpty()) {
                                Text("No chat messages checked since the app started.", color = textSecondary, fontSize = 11.sp)
                            }
                            val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
                            chatChecks.forEach { e ->
                                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text("${fmt.format(Date(e.time))}  ${e.source}", color = textSecondary, fontSize = 10.sp)
                                    Text("\"${e.snippet}\"", color = textPrimary, fontSize = 11.sp)
                                    Text(e.result, color = accentColor, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── SUMMARY CARDS ROW ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryCard(
                        modifier = Modifier.weight(1f),
                        icon = "📋",
                        label = "Pending Tasks",
                        value = tasks.size.toString(),
                        bgColor = surfaceColor,
                        accentColor = accentColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary
                    )
                    SummaryCard(
                        modifier = Modifier.weight(1f),
                        icon = "🔴",
                        label = "Urgent",
                        value = tasks.count { it.priority == 3 }.toString(),
                        bgColor = surfaceColor,
                        accentColor = Color(0xFFEF4444),
                        textPrimary = textPrimary,
                        textSecondary = textSecondary
                    )
                }
            }

            // ── QUICK ACTION CHIPS ───────────────────────────────────────────
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        SuggestionChip(
                            onClick = { editingTask = TaskEntity(title = "") },
                            label = { Text("➕ Add Task", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF2A2A44),
                                labelColor = textPrimary
                            )
                        )
                    }
                    item {
                        SuggestionChip(
                            onClick = { showMemoryDialog = true },
                            label = { Text("🧠 Memory Graph", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF381E5F),
                                labelColor = Color(0xFFD8B4FE)
                            )
                        )
                    }
                    item {
                        SuggestionChip(
                            onClick = { showIndexedFilesDialog = true },
                            label = { Text("📁 View Files", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF1E3A4B),
                                labelColor = Color(0xFF38BDF8)
                            )
                        )
                    }
                    item {
                        SuggestionChip(
                            onClick = { viewModel.addSampleTasks() },
                            label = { Text("+ Sample Tasks", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF2A2A44),
                                labelColor = textPrimary
                            )
                        )
                    }
                    item {
                        SuggestionChip(
                            onClick = {
                                if (!hasLocationPermission) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                                viewModel.addSampleGeofenceTask()
                            },
                            label = { Text("📍 + Sample Geofence", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF1E3A5F),
                                labelColor = Color(0xFF38BDF8)
                            )
                        )
                    }
                    item {
                        SuggestionChip(
                            onClick = { viewModel.testGeminiConnection() },
                            label = { Text("⚡ Test Gemini AI", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF2A2A44),
                                labelColor = textPrimary
                            )
                        )
                    }
                    item {
                        SuggestionChip(
                            onClick = { launchVoiceAssistant() },
                            label = { Text("🎙️ Voice Assistant", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF2A2A44),
                                labelColor = textPrimary
                            )
                        )
                    }
                }
            }

            item {
                Text(
                    "Upcoming Tasks",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            // ── TASK LIST OR EMPTY STATE ──────────────────────────────────────
            if (tasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor, RoundedCornerShape(16.dp))
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("✅", fontSize = 36.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("All clear!", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("No pending tasks.", color = textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.addSampleTasks() },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                            ) {
                                Text("+ Load 3 Sample Tasks")
                            }
                        }
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onClick = { editingTask = task },
                        onComplete = { viewModel.completeTask(task.id) },
                        onDelete = { viewModel.deleteTask(task.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // ── SETTINGS & DIAGNOSTICS DIALOG ─────────────────────────────────────────
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = surfaceColor,
            title = {
                Text("PAA Settings & Diagnostics", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("• AI Engine: Gemini 2.0 Flash (gemini-2.0-flash)", color = textSecondary, fontSize = 12.sp)
                    Text("• Status: Active & Connected via .env", color = textSecondary, fontSize = 12.sp)

                    Spacer(Modifier.height(4.dp))

                    // 1. Folder indexing for device file intelligence
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            openDocumentTreeLauncher.launch(null)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 Index Device Files (SAF)", fontSize = 13.sp)
                    }

                    // 2. Floating assistant bubble toggle
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                val serviceIntent = Intent(context, FloatingPillService::class.java)
                                if (isFloatingPillActive) {
                                    context.stopService(serviceIntent)
                                    isFloatingPillActive = false
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                    isFloatingPillActive = true
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isFloatingPillActive) Color(0xFF22C55E) else textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isFloatingPillActive) "🟢 Stop Floating Bubble" else "🟣 Start Floating Bubble",
                            fontSize = 13.sp
                        )
                    }

                    // System Notifications Permission Status
                    val areNotifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (areNotifsEnabled) Color(0xFF22C55E) else Color(0xFFEF4444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (areNotifsEnabled) "🟢 App Notifications (Enabled)" else "🔴 Enable App Notifications (Required)",
                            fontSize = 13.sp
                        )
                    }

                    // Exact Alarms Permission Status (Android 12+)
                    val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmMgr.canScheduleExactAlarms()
                    } else true

                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (canScheduleExact) Color(0xFF22C55E) else Color(0xFFEF4444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (canScheduleExact) "🟢 Exact Alarms & Reminders (Enabled)" else "🔴 Enable Exact Alarms & Reminders",
                            fontSize = 13.sp
                        )
                    }

                    // 3. Notification Access for WhatsApp sync
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔔 WhatsApp Notification Access (Incoming)", fontSize = 13.sp)
                    }

                    // 3b. Accessibility Service for typing commitments in WhatsApp
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⌨️ WhatsApp Typed Chat Access (Accessibility)", fontSize = 13.sp)
                    }

                    // 4. Location & Geofencing Permissions
                    val isLocGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    OutlinedButton(
                        onClick = {
                            if (!isLocGranted) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isLocGranted) Color(0xFF22C55E) else Color(0xFF38BDF8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isLocGranted) "📍 Location Access: Granted (Geofencing Active)" else "📍 Enable Location & Geofencing",
                            fontSize = 13.sp
                        )
                    }

                    // 5. Exact Alarm check on Android 12+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val alarmManager = context.getSystemService(AlarmManager::class.java)
                        val canExact = alarmManager?.canScheduleExactAlarms() ?: false
                        if (!canExact) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("⚠️ Enable Exact Alarms", fontSize = 13.sp)
                            }
                        }
                    }

                    // 5. Test Gemini API
                    Button(
                        onClick = {
                            viewModel.testGeminiConnection()
                            showSettingsDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧪 Test Gemini API Connection", fontSize = 13.sp)
                    }

                    // 6. Sample Tasks
                    OutlinedButton(
                        onClick = {
                            viewModel.addSampleTasks()
                            showSettingsDialog = false
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Load Sample Tasks", fontSize = 13.sp)
                    }

                    // 7. Personal Memory Graph Viewer
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            showMemoryDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC084FC)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧠 Personal Memory Graph (${memoryFacts.size} facts)", fontSize = 13.sp)
                    }

                    // 8. Indexed Files Viewer
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            showIndexedFilesDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 View Indexed Files (${indexedFiles.size} files)", fontSize = 13.sp)
                    }

                    // 9. Open Voice Assistant
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            launchVoiceAssistant()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🎙️ Open Voice Assistant", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done", color = accentColor)
                }
            }
        )
    }

    // ── TASK EDIT & CREATION DIALOG ───────────────────────────────────────────
    if (editingTask != null) {
        TaskEditDialog(
            task = editingTask!!,
            onDismiss = { editingTask = null },
            onSave = { updated ->
                viewModel.createOrUpdateTask(updated)
            }
        )
    }

    // ── PERSONAL MEMORY GRAPH DIALOG ──────────────────────────────────────────
    if (showMemoryDialog) {
        MemoryGraphDialog(
            onDismiss = { showMemoryDialog = false },
            memoryFacts = memoryFacts,
            onAddFact = { topic, content ->
                viewModel.addFact(topic, content)
            },
            onDeleteFact = { factId ->
                viewModel.deleteFact(factId)
            }
        )
    }

    // ── INDEXED FILES VIEWER DIALOG ───────────────────────────────────────────
    if (showIndexedFilesDialog) {
        IndexedFilesDialog(
            onDismiss = { showIndexedFilesDialog = false },
            indexedFiles = indexedFiles,
            onIndexMore = {
                showIndexedFilesDialog = false
                openDocumentTreeLauncher.launch(null)
            }
        )
    }
}

@Composable
private fun SummaryCard(
    modifier: Modifier,
    icon: String, label: String, value: String,
    bgColor: Color, accentColor: Color, textPrimary: Color, textSecondary: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = bgColor
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = accentColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(label, color = textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val priorityColor = when (task.priority) {
        3 -> Color(0xFFEF4444)
        2 -> Color(0xFFF59E0B)
        1 -> Color(0xFF3B82F6)
        else -> Color(0xFF6B7280)
    }
    val priorityLabel = when (task.priority) { 3 -> "URGENT"; 2 -> "HIGH"; 1 -> "MEDIUM"; else -> "LOW" }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E1E34),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Priority bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .background(priorityColor, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = priorityColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            priorityLabel,
                            color = priorityColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    task.locationName?.let { loc ->
                        val transitionLabel = if (task.geofenceTransition == 2) "Exit" else "Arrive"
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0284C7).copy(alpha = 0.2f)
                        ) {
                            Text(
                                "📍 $loc ($transitionLabel)",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    task.dueTimestamp?.let { ts ->
                        Text(
                            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts)),
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Complete button
            IconButton(onClick = onComplete) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Complete", tint = Color(0xFF22C55E))
            }
            // Delete button
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF64748B))
            }
        }
    }
}

// ── TASK DETAIL / EDIT DIALOG ─────────────────────────────────────────────────
@Composable
private fun TaskEditDialog(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit
) {
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var priority by remember { mutableIntStateOf(task.priority) }
    var locationName by remember { mutableStateOf(task.locationName ?: "") }
    var recurrenceRule by remember { mutableStateOf(task.recurrenceRule ?: "") }
    var dueTimestamp by remember { mutableStateOf(task.dueTimestamp) }

    val isNew = task.id == 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        title = {
            Text(
                if (isNew) "➕ Create New Task" else "✏️ Edit Task",
                color = Color(0xFFE2E8F0),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / Notes") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                // Priority Selection
                Text("Priority Level:", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val priorities = listOf(
                        0 to "Low",
                        1 to "Medium",
                        2 to "High",
                        3 to "Urgent"
                    )
                    priorities.forEach { (p, label) ->
                        val selected = priority == p
                        val btnColor = when (p) {
                            3 -> Color(0xFFEF4444)
                            2 -> Color(0xFFF59E0B)
                            1 -> Color(0xFF3B82F6)
                            else -> Color(0xFF6B7280)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) btnColor else Color(0xFF25253E),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { priority = p }
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.White else Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Due Time Presets
                Text("Due Time:", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val now = System.currentTimeMillis()
                    val tonightMillis = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 20)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                        if (timeInMillis <= now) {
                            timeInMillis = now + (2 * 3600_000L) // +2 hours if past 8 PM
                        }
                    }.timeInMillis
                    val presets = listOf(
                        "1 hour" to (now + 3600_000L),
                        "Tonight" to tonightMillis,
                        "Tomorrow" to (java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0) }.timeInMillis),
                        "None" to null
                    )
                    presets.forEach { (lbl, ts) ->
                        val selected = (dueTimestamp == ts) || (ts != null && dueTimestamp != null && Math.abs(dueTimestamp!! - ts) < 60_000L)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFF7C3AED) else Color(0xFF25253E),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { dueTimestamp = ts }
                        ) {
                            Text(
                                lbl,
                                color = if (selected) Color.White else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Location Reminder
                OutlinedTextField(
                    value = locationName,
                    onValueChange = { locationName = it },
                    label = { Text("📍 Location Alert (e.g. Whole Foods)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Recurrence Rule
                Text("Recurrence:", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val recurrences = listOf(
                        "" to "None",
                        "RRULE:FREQ=DAILY" to "Daily",
                        "RRULE:FREQ=WEEKLY" to "Weekly"
                    )
                    recurrences.forEach { (rule, label) ->
                        val selected = recurrenceRule == rule
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFF7C3AED) else Color(0xFF25253E),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { recurrenceRule = rule }
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.White else Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val updated = task.copy(
                            title = title.trim(),
                            description = description.ifBlank { null },
                            priority = priority,
                            locationName = locationName.ifBlank { null },
                            recurrenceRule = recurrenceRule.ifBlank { null },
                            dueTimestamp = dueTimestamp
                        )
                        onSave(updated)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                enabled = title.isNotBlank()
            ) {
                Text(if (isNew) "Create Task" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

// ── PERSONAL MEMORY GRAPH DIALOG ──────────────────────────────────────────────
@Composable
private fun MemoryGraphDialog(
    onDismiss: () -> Unit,
    memoryFacts: List<MemoryFactEntity>,
    onAddFact: (String, String) -> Unit,
    onDeleteFact: (Long) -> Unit
) {
    var newTopic by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 Personal Memory Graph", color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF7C3AED).copy(alpha = 0.2f)
                ) {
                    Text(
                        "${memoryFacts.size} facts",
                        color = Color(0xFFC084FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "PAA uses these memories to personalize advice, recall dates, and answer context-aware queries.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                // Add Fact Form
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF25253E),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("+ Add Personal Memory Fact", color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = newTopic,
                            onValueChange = { newTopic = it },
                            placeholder = { Text("Topic (e.g. Travel, Family, Preferences)", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newContent,
                            onValueChange = { newContent = it },
                            placeholder = { Text("Fact (e.g. Flight preference: window seat)", fontSize = 12.sp) },
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (newContent.isNotBlank()) {
                                    onAddFact(newTopic.ifBlank { "General" }, newContent.trim())
                                    newTopic = ""
                                    newContent = ""
                                }
                            },
                            enabled = newContent.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Save Fact", fontSize = 12.sp)
                        }
                    }
                }

                // Fact List
                if (memoryFacts.isEmpty()) {
                    Text(
                        "No personal facts stored yet. Speak to PAA (e.g. 'Remember that my wifi password is...') or add one above!",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    memoryFacts.forEach { fact ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF202038),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF7C3AED).copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            fact.keyTopic.uppercase(),
                                            color = Color(0xFFC084FC),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(fact.factContent, color = Color(0xFFE2E8F0), fontSize = 13.sp)
                                }
                                IconButton(
                                    onClick = { onDeleteFact(fact.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Fact", tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF7C3AED))
            }
        }
    )
}

// ── INDEXED FILES VIEWER DIALOG ───────────────────────────────────────────────
@Composable
private fun IndexedFilesDialog(
    onDismiss: () -> Unit,
    indexedFiles: List<FileIndexEntity>,
    onIndexMore: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery, indexedFiles) {
        if (searchQuery.isBlank()) indexedFiles
        else indexedFiles.filter { it.fileName.contains(searchQuery, ignoreCase = true) || it.mimeType.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📁 Indexed Device Files", color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF38BDF8).copy(alpha = 0.2f)
                ) {
                    Text(
                        "${indexedFiles.size} files",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "PAA indexes these documents so Gemini can answer questions, summarize PDFs, and extract tasks from your device files.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search files...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (indexedFiles.isEmpty()) "No files indexed yet.\nTap 'Index Folder' to pick a folder via SAF." else "No matching files.",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { file ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF202038),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        when {
                                            file.mimeType.contains("pdf") -> "📄"
                                            file.mimeType.contains("image") -> "🖼️"
                                            file.mimeType.contains("audio") -> "🎵"
                                            else -> "📁"
                                        },
                                        fontSize = 18.sp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.fileName, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(
                                            "${file.mimeType} • ${formatFileSize(file.sizeBytes)}",
                                            color = Color(0xFF64748B),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onIndexMore,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
            ) {
                Text("+ Index Folder", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF94A3B8))
            }
        }
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes.toDouble() / (1024 * 1024))
    }
}

@Composable
private fun PermissionLine(ok: Boolean, label: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "❌", fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color(0xFFE2E8F0), fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Turn on", fontSize = 12.sp) }
    }
}
