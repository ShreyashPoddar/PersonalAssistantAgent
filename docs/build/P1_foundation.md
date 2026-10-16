# Phase 1 — Foundation: action framework, guard, confirm, audit, screen-reading switch, app launcher, SOS, hardening

Read `ARCHITECTURE.md` first. Tasks in order: T1 → T13. Every task ends with build + tests + commit (README workflow).

---

## P1-T1 — Action framework types
**Goal:** the typed actions every skill uses. No behaviour yet.
**Depends on:** —
**Read first:** `ARCHITECTURE.md` (Core types), `core/router/LocalTaskParser.kt` lines 1–60 (style).

**Create**
- `core/agent/Action.kt` — exactly the `RiskTier` enum and `Action` sealed class from `ARCHITECTURE.md`.
  `fmt(atMillis)` = `SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(atMillis))` (private top-level fun).
- `core/agent/CommandSource.kt` — the enum.
- `core/agent/Skill.kt` — `interface Skill<A : Action>` and `data class ActionResult(val ok: Boolean, val message: String)`.

**Tests:** `app/src/test/java/com/paa/assistant/core/agent/ActionTest.kt`
- every `Action` subclass has the documented `tier`;
- `SendMessage.summary()` contains the contact name and body; `PlaceOrder.summary()` shows `₹123.45` for 12345 paise.

**Done when:** build + tests pass.

---

## P1-T2 — Agent settings, contact rules, Guard
**Goal:** one place that decides whether an action may run, needs a tap, needs a fingerprint, or is refused.
**Depends on:** T1.
**Read first:** `data/db/AppDatabase.kt` (version 5, migrations), `data/db/FeedbackDao.kt` (DAO style),
`di/DatabaseModule.kt` (how DAOs are provided), `core/audio/VoicePrint.kt` (`VoicePrint.isOn(context)`).

**Create**
1. `data/models/ContactRuleEntity.kt`
   ```kotlin
   @Entity(tableName = "contact_rules")
   data class ContactRuleEntity(
       @PrimaryKey val contactKey: String,      // normalised name or phone number, lowercase
       val outward: String = "PREVIEW",         // AUTO | PREVIEW | ALWAYS_ASK
       val autoReplyDuringCall: Boolean = false,
       val messagedBefore: Boolean = false,
       val updatedAt: Long = System.currentTimeMillis()
   )
   ```
2. `data/models/AuditEntryEntity.kt` (used in T4, created now to do one migration)
   ```kotlin
   @Entity(tableName = "audit_log")
   data class AuditEntryEntity(
       @PrimaryKey(autoGenerate = true) val id: Long = 0,
       val at: Long, val actionType: String, val tier: String, val source: String,
       val summary: String, val verdict: String, val ok: Boolean, val resultMessage: String
   )
   ```
3. `data/db/AgentDao.kt` with `@Dao interface ContactRuleDao` (get(key), upsert(rule), all(): Flow) and
   `@Dao interface AuditDao` (insert(e), recent(limit): Flow<List<…>>, deleteOlderThan(time)).
4. `AppDatabase.kt`: add both entities, `version = 6`, `MIGRATION_5_6` with the two `CREATE TABLE IF NOT EXISTS`
   statements (copy column types from the generated schema: TEXT NOT NULL, INTEGER NOT NULL, PRIMARY KEY …),
   register in `addMigrations(...)`, add `abstract fun contactRuleDao(): ContactRuleDao` and `auditDao()`.
   Provide both DAOs in `di/DatabaseModule.kt` like the existing ones.
5. `core/agent/AgentSettings.kt` — `@Singleton class AgentSettings @Inject constructor(@ApplicationContext ctx)`
   with prefs `paa_agent`: `lockdown: Boolean`, `paused: Boolean` (kill switch), `voiceLockOn(): Boolean` =
   `VoicePrint.isOn(ctx)`.
6. `core/agent/Guard.kt`
   ```kotlin
   sealed class Verdict { object Allow : Verdict(); data class Confirm(val biometric: Boolean) : Verdict(); data class Deny(val reason: String) : Verdict() }

   @Singleton class Guard @Inject constructor(private val settings: AgentSettings, private val rules: ContactRuleDao) {
       suspend fun check(action: Action, source: CommandSource, preConfirmed: Boolean = false): Verdict =
           decide(action, source, preConfirmed, settings.lockdown, settings.paused, settings.voiceLockOn(),
                  ruleFor(action))
       private suspend fun ruleFor(a: Action) = contactKey(a)?.let { rules.get(it) }
       companion object {
           /** Pure policy — unit-tested. */
           fun decide(a: Action, s: CommandSource, preConfirmed: Boolean, lockdown: Boolean, paused: Boolean,
                      voiceLock: Boolean, rule: ContactRuleEntity?): Verdict { … }
           fun contactKey(a: Action): String? = when (a) { is Action.SendMessage -> a.contactName; is Action.SendSms -> a.number; is Action.PlaceCall -> a.number; is Action.SendEmail -> a.to; else -> null }?.trim()?.lowercase()
       }
   }
   ```
   **`decide` rules (implement exactly, in this order):**
   1. `a.tier == SOS` → `Allow` if `s` is `OWNER_VOICE` or `OWNER_UI`, else `Deny("SOS only from the owner")`.
   2. `paused` → `Deny("PAA is paused")`.
   3. `lockdown` and tier not in {READ, PRIVATE_REVERSIBLE} → `Deny("Lockdown is on")`.
   4. tier `READ` → `Allow`.
   5. tier `PRIVATE_REVERSIBLE` → `Allow`.
   6. tier `OUTWARD`:
      - `s == SYSTEM_EVENT` → `Allow` only if `rule?.autoReplyDuringCall == true` and the action is a `SendMessage`
        (the existing "busy on a call" auto-reply); else `Deny("Messages only on the owner's command")`.
      - owner/schedule: `rule == null || !rule.messagedBefore` → `Confirm(biometric = true)` (first time to this contact);
        `rule.outward == "ALWAYS_ASK"` → `Confirm(true)`; `"AUTO"` → `Allow`;
        `"PREVIEW"` → if `preConfirmed` → `Allow` else `Confirm(false)`.
      - then: if `s == OWNER_VOICE && !voiceLock` and the result is `Allow` or `Confirm(false)` → `Confirm(true)`
        (anyone near the phone could have spoken).
   7. tier `MONEY` → `s` must be `OWNER_VOICE`/`OWNER_UI`, else `Deny("Payments only on the owner's command")`;
      always `Confirm(biometric = true)` (even if `preConfirmed`).

**Tests:** `core/agent/GuardTest.kt` — table-driven, one case per rule above (≥ 16 cases), including:
lockdown blocks OUTWARD but not SetAlarm; SYSTEM_EVENT SendMessage denied unless autoReplyDuringCall; first message
to a contact needs biometric; voice without voice lock upgrades to biometric; MONEY from SCHEDULE denied; SOS from
SYSTEM_EVENT denied.

**Done when:** build + tests pass; app starts on the emulator (migration ran — check logcat has no Room error).

---

## P1-T3 — Fingerprint confirm bound to the action (BiometricGate + ConfirmActivity)
**Goal:** an action that needs confirmation shows its typed summary; a biometric confirm produces a signature over
that exact action, so nothing else can be slipped in.
**Depends on:** T1, T2, P0-T3 (StrongBox yes/no).
**Read first:** `gradle/libs.versions.toml`, `app/build.gradle.kts` dependencies block, `ui/theme/Theme.kt`.

**Steps**
1. Dependencies (version catalog): `androidx-biometric = { group = "androidx.biometric", name = "biometric", version = "1.1.0" }`
   and `androidx-fragment-ktx = { group = "androidx.fragment", name = "fragment-ktx", version = "1.8.5" }`
   (skip a line if it already exists); add `implementation(libs.androidx.biometric)` and `implementation(libs.androidx.fragment.ktx)`.
2. `core/agent/BiometricGate.kt` (`@Singleton`):
   - `ensureKey()` — AndroidKeyStore alias `paa_confirm_v1`, EC `secp256r1`, `PURPOSE_SIGN`, `DIGEST_SHA256`,
     `setUserAuthenticationRequired(true)`, on API ≥ 30 `setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)`,
     `setInvalidatedByBiometricEnrollment(true)`, `setIsStrongBoxBacked(true)` only if
     `packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)`.
     If key creation throws `KeyPermanentlyInvalidatedException` later, delete and recreate the alias.
   - `fun signatureForPrompt(): Signature` = `Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }`.
   - `fun digest(action: Action, requestId: String): ByteArray` = SHA-256 of `"$requestId|${action::class.simpleName}|${action.summary()}"`.
   - `fun verify(action, requestId, sig: ByteArray): Boolean` — verify with the public key.
3. `core/agent/ConfirmBroker.kt` (`@Singleton`): `suspend fun ask(action: Action, source: CommandSource, biometric: Boolean): Boolean`
   — creates `requestId = UUID`, stores a `CompletableDeferred<Boolean>` in a map, starts `ConfirmActivity`
   (`FLAG_ACTIVITY_NEW_TASK`, extras: requestId), awaits with `withTimeoutOrNull(60_000)` → false on timeout;
   `fun complete(requestId, approved: Boolean, signature: ByteArray?)` — for biometric requests `approved` counts only
   if `biometricGate.verify(...)` is true.
   Keep the pending `Action` in the broker's map (never pass the action through Intent extras — the activity reads
   it from the broker by `requestId`).
4. `core/agent/ConfirmActivity.kt` — `@AndroidEntryPoint class ConfirmActivity : FragmentActivity()`:
   - shows over the lock screen (`setShowWhenLocked(true)` on API ≥ 27), Compose UI: big title by tier
     ("Send this?", "Pay?"), `action.summary()`, source ("You asked by voice"), buttons **Cancel** / **Confirm**.
   - Confirm with `biometric == false` → `broker.complete(id, true, null)`; finish.
   - Confirm with `biometric == true` → `BiometricPrompt(this, executor, callback).authenticate(promptInfo, CryptoObject(gate.signatureForPrompt()))`,
     `promptInfo` title = summary, `setAllowedAuthenticators(BIOMETRIC_STRONG)`, negative button "Cancel".
     `onAuthenticationSucceeded` → `val sig = result.cryptoObject!!.signature!!.apply { update(gate.digest(action, id)) }.sign()` →
     `broker.complete(id, true, sig)`; finish. Errors/cancel → `complete(id, false, null)`.
   - Back press / `onDestroy` without a decision → `complete(id, false, null)`.
5. Manifest: `<activity android:name=".core.agent.ConfirmActivity" android:exported="false" android:excludeFromRecents="true" android:theme="@style/…same as VoiceOverlayActivity…" />`.

**Tests:** `BiometricGateDigestTest` — digest changes when amount/body/requestId changes (pure function; extract
the string builder into a companion `digestInput(...)` so it is testable without Keystore).

**Verify on phone:** temporary debug button in `MainScreen` (inside `if (BuildConfig.DEBUG)`) that submits a
`PlaceOrder("test", "1 item", 100)` through the broker → fingerprint prompt appears → approve → toast "approved".
Remove the button before committing.

---

## P1-T4 — Executor, skill registry, audit log screen
**Goal:** the single entry point that runs actions and records them.
**Depends on:** T1–T3.

**Create**
- `core/agent/SkillRegistry.kt` — `@Singleton class SkillRegistry @Inject constructor(...skills...)` with
  `fun forAction(a: Action): Skill<Action>?` using `when (a)`. Start with no skills (returns null) — later tasks add them.
- `core/agent/Executor.kt`
  ```kotlin
  @Singleton class Executor @Inject constructor(private val guard: Guard, private val broker: ConfirmBroker,
      private val skills: SkillRegistry, private val audit: AuditDao, private val rules: ContactRuleDao) {
      suspend fun submit(action: Action, source: CommandSource, preConfirmed: Boolean = false): ActionResult {
          val verdict = guard.check(action, source, preConfirmed)
          val approved = when (verdict) {
              Verdict.Allow -> true
              is Verdict.Deny -> return record(action, source, verdict, ActionResult(false, verdict.reason))
              is Verdict.Confirm -> broker.ask(action, source, verdict.biometric)
          }
          if (!approved) return record(action, source, verdict, ActionResult(false, "Cancelled"))
          val skill = skills.forAction(action) ?: return record(action, source, verdict, ActionResult(false, "Not supported yet"))
          val result = runCatching { skill.run(action) }.getOrElse { ActionResult(false, "Failed: ${it.javaClass.simpleName}") }
          if (result.ok) Guard.contactKey(action)?.let { markMessaged(it) }
          return record(action, source, verdict, result)
      }
  }
  ```
  `record` inserts an `AuditEntryEntity` (summary = `action.summary()`), logs `DetectionLog.add("agent", action::class.simpleName!!, result.ok/ message)`
  (**no summary in DetectionLog**), returns the result. `markMessaged` upserts the rule with `messagedBefore = true`.
- Retention: in `PAAApplication.onCreate` (IO coroutine) `audit.deleteOlderThan(now - 90 days)`.
- UI: in `ui/main/MainScreen.kt` add an **"Activity"** card listing the last 50 audit entries (time, ✅/❌, summary,
  verdict). Plus a link "Contact rules" → simple list screen to set AUTO / PREVIEW / ALWAYS_ASK per contact
  (reuse `ContactResolver` for names).

**Tests:** `ExecutorTest` with fakes (fake Guard via a small interface or by constructing Guard with fake DAOs):
denied action never reaches the skill; cancelled confirm never reaches the skill; successful OUTWARD marks the
contact as messaged; every path writes exactly one audit entry.

---

## P1-T5 — Route existing actions through the Executor
**Goal:** what PAA already does (WhatsApp send, timers/alarms, calls, busy auto-reply) goes through Guard + audit.
**Depends on:** T4.
**Read first:** `ui/overlay/VoiceOverlayViewModel.kt` (`executeLocally`, `prepareMessage`, `confirmSend`),
`core/messaging/WhatsAppSender.kt`, `services/NotificationListener.kt` (autoReplyDuringCall call site),
`core/router/LocalCommandExecutor.kt`.

**Steps**
1. `core/skills/MessageSkill.kt` — `Skill<Action.SendMessage>`; for `app == "WhatsApp"` call the existing
   `WhatsAppSender.send(context, contact, body)` (resolve `Contact` with `ContactResolver` by `contactId`).
2. `core/skills/AlarmSkill.kt` — wraps whatever `SET_TIMER` / alarm code the overlay uses today.
3. `core/skills/CallSkill.kt` — `ACTION_CALL` needs `CALL_PHONE` (add permission + runtime request) — else
   `ACTION_DIAL` (owner taps call). Use `ACTION_CALL` only when permission granted.
4. Register the three in `SkillRegistry`.
5. `VoiceOverlayViewModel.confirmSend(...)`: build `Action.SendMessage(...)` and call
   `executor.submit(action, CommandSource.OWNER_VOICE, preConfirmed = true)` (the overlay already showed the
   exact text and the owner tapped Send). Show `result.message`.
6. `NotificationListener` busy auto-reply: replace the direct `WhatsAppSender.autoReplyDuringCall(...)` call with
   `executor.submit(Action.SendMessage("WhatsApp", key, chatName, <same fixed text>), CommandSource.SYSTEM_EVENT)`.
   Make the "contacts allowed for auto-reply" setting write `autoReplyDuringCall = true` into `contact_rules`.
7. Keep behaviour identical otherwise.

**Tests:** existing `SendMessageTest` must still pass; add a test that the auto-reply action is denied when the
contact has no `autoReplyDuringCall` rule (via `Guard.decide`).
**Verify on phone:** "Oyee PA, message <a test contact> test" → first time asks fingerprint (new contact) → sends;
Activity card shows it.

---

## P1-T6 — Owner-controlled "screen reading off" switch
**Goal:** the owner can turn PAA's screen reading (accessibility service) off and on with one tap, so they can
switch it off while banking. This is **owner-initiated**, visible, and deliberate. PAA does **not** watch for bank
apps and does **not** try to hide itself from them or race their launch — that would be circumventing a security
control, and we don't build it. Banking stays safe because *the owner* decides when PAA can see the screen; the
reliable separation is the profile split (P0-T7) or keeping banking manual.
**Depends on:** T4.
**Read first:** `services/ChatTaskAccessibilityService.kt` (its component name), `services/PAATileService.kt`
(Quick Settings tile pattern), `services/WakeListenerService.kt` (`setEnabled` prefs pattern).

**Steps**
1. Manifest: `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" tools:ignore="ProtectedPermissions" />`.
   (This is only a convenience so the toggle below doesn't force the owner to dig into Settings every time. If the
   owner chooses not to grant it, every toggle instead opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` for a manual flip.)
2. `core/shield/A11yToggle.kt`
   - `val SERVICE = ComponentName(ctx, ChatTaskAccessibilityService::class.java)`
   - `fun canToggle(ctx) = ctx.checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED`
   - `fun isEnabled(ctx): Boolean` — read `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, split on `:`, parse each
     with `ComponentName.unflattenFromString`, compare to `SERVICE` (handles both `pkg/.Cls` and `pkg/pkg.Cls` forms).
   - `fun setEnabled(ctx, on: Boolean): Boolean` — if `!canToggle` return false (caller opens Settings instead);
     else rebuild the list keeping **all other services unchanged**, write it back; when turning on also
     `Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)`; return true.
   - Pure helper `fun editList(current: String?, me: ComponentName, on: Boolean): String` (unit-tested).
   There is **no** foreground watcher, no polling, and nothing that reacts to which app is open. The service only
   changes state when the owner acts (the toggle, the tile, or the voice command below).
3. Quick Settings tile `core/shield/ScreenReadingTileService.kt` (register in manifest like `PAATileService`,
   label "PAA screen reading"): tile shows ON when `A11yToggle.isEnabled`; tap → if `canToggle` flip it and
   update the tile; else `startActivityAndCollapse` to `ACTION_ACCESSIBILITY_SETTINGS`. So the owner can turn
   PAA's reading off from the shade before banking and on again after, in one tap.
4. Settings card **"Screen reading"** in `MainScreen`: a switch bound to `A11yToggle.isEnabled`/`setEnabled`, the
   grant status, a button "Open Usage access"→no (not needed), a button "Open Accessibility settings", and this
   plain text: *"Turn this off while you use banking or UPI apps. Banks don't allow assistant apps to read their
   screens, and PAA respects that. For the least friction, keep your banking apps in a separate profile (see the
   guide) where PAA isn't enabled at all."*
5. Voice: add `LocalIntent`-level phrases handled in the overlay (not a new Action — it's a local setting change):
   "stop reading my screen" / "screen reading off" / "banking mode" → `A11yToggle.setEnabled(false)` + reply
   "Screen reading off. Say 'screen reading on' when you're done."; "screen reading on" → back on. If `!canToggle`,
   reply with how to flip it in Settings.
6. Optional one-time grant so the toggle works in-app (owner's choice; agent runs it only if the owner says yes,
   Developer options on just for this, then off again):
   `adb -s <serial> shell pm grant com.paa.assistant android.permission.WRITE_SECURE_SETTINGS`.
   The grant survives reboots and updates (not uninstall). It does **not** let PAA do anything automatically — it
   only lets the owner's own toggle/tile/voice command take effect without opening Settings.

**Tests:** `A11yToggleTest` for `editList`: adds without duplicates, removes only ours, keeps others, handles
null/empty, handles the short `pkg/.Cls` form.
**Verify on phone:** tap the tile → PAA's accessibility turns off (Settings → Accessibility shows PAA off); tap
again → on. Voice "banking mode" turns it off. No automatic changes happen when any other app opens.

---

## P1-T7 — Banking separation guide (no automation)
**Goal:** give the owner the reliable, legitimate way to run PAA and banking on one phone — a separate profile —
documented, with PAA simply not enabled there. No shortcuts that hide PAA, no launch interception.
**Depends on:** T6, P0-T7 result.

**Steps**
1. `docs/build/BANKING_SEPARATION.md` — owner guide:
   - **Recommended:** keep banking/UPI apps in a **separate user profile** (Settings → System → Users → Add user,
     or vivo's multi-user) where PAA and its accessibility service are **not** installed/enabled. Switch to that
     user for banking. Accessibility settings are per-user, so PAA in the main profile never affects them.
   - **Simplest:** keep banking manual in the main profile and flip the T6 tile off before you bank.
   - **If you want a reminder:** PAA can show a one-tap "screen reading is ON" chip (links to the tile) — never an
     automatic change.
2. Settings card **"Banking"** in `MainScreen`: short text pointing to the guide + a button "How to separate
   banking" that shows it. No package list, no launch buttons, no foreground monitoring.
3. PAA must **not**: detect which app is in the foreground to change its own state; intercept or wrap the launch of
   banking apps; attempt to appear absent to any app. If a future task needs foreground detection for a *different*
   purpose, it must not be used to gate accessibility around banking apps.

**Verify on phone:** owner confirms the profile-split path from P0-T7 works (banking app runs in the second profile
with PAA enabled in the main one), or chooses the manual-toggle path. Record which in `results/phase0.md`.

---

## P1-T8 — Instant app launcher ("open insta")
**Goal:** "Oyee PA, open Instagram / insta kholo / launch gpay" opens the app in < 1 s, no cloud.
**Depends on:** T4.
**Read first:** `core/router/LocalTaskParser.kt` (how intents and regexes are written, `SEND_MESSAGE` parse),
`core/router/IntentRouter.kt` (`route`), `ui/overlay/VoiceOverlayViewModel.kt` (`executeLocally`). Manifest
already has a `<queries>` block — add `<intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent>` inside it if missing.

**Steps**
1. `core/skills/AppDirectory.kt` — `@Singleton`; `fun apps(): List<AppEntry(label, pkg)>` from
   `queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)`, cached 10 min.
   Pure companion `fun match(query: String, apps: List<AppEntry>): AppEntry?`:
   - normalise: lowercase, remove non-alphanumerics; strip words "app", "the", "open", "kholo", "khol", "launch", "start", "chalao";
   - aliases: `insta→instagram, yt→youtube, wa/whatsapp→whatsapp, gpay→google pay, fk→flipkart, mmt→makemytrip, amazon→amazon shopping, maps→maps, settings→settings, cam→camera`;
   - score: exact label 100 > label starts with query 80 > any label word starts with query 60 > contains 40 >
     Levenshtein ≤ 2 on a label word 30; ties → shorter label; return null if best < 30.
2. `LocalTaskParser`: add `LocalIntent.OPEN_APP`; patterns (case-insensitive, Hinglish):
   `^(?:please\s+)?(?:open|launch|start)\s+(.+)$`, `^(.+?)\s+(?:kholo|khol do|khol|chalao|open karo)$`.
   Put the app phrase in `taskTitle`, confidence 0.95. It must not steal "open the file …" (keep the cloud keyword
   behaviour: if the phrase contains "file", "pdf", "document", "link" → not OPEN_APP).
3. `IntentRouter.route`: add `OPEN_APP` to the always-local set.
4. `core/skills/OpenAppSkill.kt` — `Skill<Action.OpenApp>`: launch the app's normal launch intent with `NEW_TASK`
   (a bank app is opened the same ordinary way as any other app — no special handling). Register it.
5. `VoiceOverlayViewModel.executeLocally`: `OPEN_APP` → match → `executor.submit(Action.OpenApp(pkg, label), OWNER_VOICE)`,
   reply "Opening $label" / "I couldn't find an app called …". Close the overlay right after launching.

**Tests:** `AppDirectoryMatchTest` (fake list of ~15 apps: insta, yt, gpay, typo "instgram", "whatsapp business"
vs "whatsapp", unknown → null); `LocalTaskParserTest` cases for open/kholo and the "open the file" exclusion.
**Verify on phone:** from the lock screen and from inside another app, "Oyee PA, open YouTube" → opens; measure
time from end of speech to app visible (target < 1.5 s). Record in results (Test 6).

---

## P1-T9 — SOS
**Goal:** one command or button sends live location to chosen contacts, calls, and keeps updating — offline-safe,
no model involved.
**Depends on:** T4.
**Read first:** `core/location/GeofenceManager.kt` (how location client is created), `services/WakeListenerService.kt`
(foreground service pattern), `core/router/LocalTaskParser.kt`.

**Steps**
1. Permissions: `SEND_SMS`, `CALL_PHONE`, `FOREGROUND_SERVICE_LOCATION` (+ existing location). SEND_SMS is
   restricted for sideloaded apps → covered by "Allow restricted settings" (P0-T2).
2. `core/sos/SosSettings.kt` — prefs: up to 5 contacts (name + number), `callTarget` ("112" or first contact),
   `siren` (on/off — off = silent SOS), `countdownSeconds` (default 5), `repeatMinutes` (5) for `durationMinutes` (60).
   Settings card "SOS" in MainScreen to edit them + **"Run a drill"** button.
3. `core/sos/SosMessage.kt` — pure: `fun text(name: String, lat: Double?, lon: Double?, accuracyM: Float?, at: Long, drill: Boolean): String`
   → `"🆘 SOS from Shreyash. Location: https://maps.google.com/?q=LAT,LON (±25 m) at 10:42 PM. Sent by PAA."`
   (no location → "Location unavailable — call me."; drill prefix "TEST — ignore:").
4. `core/sos/SosService.kt` — foreground service type `location`:
   - countdown notification with **Cancel** action for `countdownSeconds` (vibrate each second); then:
   - get location: `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY, token)` with 15 s
     timeout, fallback `lastLocation`;
   - `SmsManager` (`getSystemService(SmsManager::class.java)` on API ≥ 31, else `SmsManager.getDefault()`)
     `sendMultipartTextMessage` to every contact;
   - if `siren` → play the alarm ringtone at max `STREAM_ALARM` volume, looping;
   - call `callTarget` with `ACTION_CALL` (fallback `ACTION_DIAL`);
   - every `repeatMinutes` until `durationMinutes`: new location → SMS again;
   - notification action **"I'm safe"** → opens `ConfirmActivity`-style device-credential check
     (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) → stop everything and SMS "I'm safe now." to the same contacts.
   - Drill mode: sends only to the owner's own number (setting), no call, no siren.
5. `core/skills/SosSkill.kt` → starts `SosService`; register. `Action.Sos` tier SOS (Guard: owner only).
6. Triggers (all call `executor.submit(Action.Sos("…"), OWNER_*)`):
   - voice: `LocalTaskParser` `LocalIntent.SOS` on exact phrases `sos`, `emergency`, `help me`, `bachao`,
     `madad karo` (whole utterance, ≤ 3 words) — router always-local;
   - big red button in `MainScreen`; Quick Settings tile `SosTileService` ("PAA SOS"); home widget button
     (in `widget/TaskGlanceWidget.kt`).
7. Tell the owner to also set up vivo's own Emergency SOS (Settings → Safety & emergency) as a backup.

**Tests:** `SosMessageTest` (with/without location, drill prefix), `LocalTaskParserTest` SOS phrases and that
"help me with the assignment" is **not** SOS.
**Verify on phone:** run a **drill** only (never a real SOS in testing): owner's own number receives the TEST SMS
with a working maps link; "I'm safe" stops it. Monthly drill reminder: create a recurring task "SOS drill" on the 1st.

---

## P1-T10 — Text sanitiser for everything PAA reads
**Goal:** untrusted text (notifications, screens, SMS, email) is cleaned and flagged before any model or rule sees it.
**Depends on:** —
**Create** `core/security/TextSanitizer.kt` (pure Kotlin, no Android):
```kotlin
data class Sanitized(val text: String, val flags: Set<String>)
object TextSanitizer {
    fun clean(raw: String, maxLen: Int = 2000): Sanitized
}
```
- remove zero-width and invisible chars: U+200B–U+200F, U+2060–U+2064, U+FEFF, U+00AD; bidi controls
  U+202A–U+202E, U+2066–U+2069 → flag `"hidden_chars"` if any removed;
- `java.text.Normalizer.normalize(s, Normalizer.Form.NFKC)`; collapse runs of whitespace; trim; cap at `maxLen`
  (flag `"truncated"`);
- flag `"instruction_like"` when it matches (case-insensitive) `ignore (all|previous|above) instructions`,
  `\b(system|assistant)\s*:`, `\bpaa\s*[,:]`, `you are now`, `new instructions`.
Use it in: `NotificationListener` (before building `ChatMessage`), `ChatTaskAccessibilityService` (each bubble),
and later SMS/email readers. `instruction_like` messages are still processed for tasks (they're data) but are
logged in `DetectionLog` as `"flagged"` and can never produce OUTWARD/MONEY actions (already true via Guard).
**Tests:** `TextSanitizerTest` — zero-width removed, bidi removed, NFKC (full-width digits → ASCII), cap, each
instruction pattern flagged, normal Hinglish not flagged.

---

## P1-T11 — Accessibility perception hardening
**Goal:** hidden/overlay/password content on screen can't reach PAA.
**Depends on:** T10.
**Read first:** `services/ChatTaskAccessibilityService.kt` (`visibleMessages`, `scanOpenChat`, `findText`).

**Steps**
1. Pure `core/security/NodeFilter.kt`: `data class NodeLite(val text: String?, val visible: Boolean, val password: Boolean, val w: Int, val h: Int, val pkg: String?)`
   and `fun keep(n: NodeLite, expectedPkg: String): Boolean` = text not blank && visible && !password && w ≥ 8 && h ≥ 8 && pkg == expectedPkg.
2. In the service, convert `AccessibilityNodeInfo` → `NodeLite` (`isVisibleToUser`, `isPassword`,
   `getBoundsInScreen`, `packageName`) and skip nodes that fail `keep`; pass texts through `TextSanitizer`.
3. Ignore events whose `event.packageName` is not in the service's configured packages (defensive).
4. Never act (tap Send) unless the window's root package is the expected app (the existing
   `tapSendIfApproved` must check `root.packageName`).
**Tests:** `NodeFilterTest`.
**Verify on phone:** WhatsApp reading still works (send yourself a message from another phone; it's scheduled).

---

## P1-T12 — Kill switch and Lockdown
**Goal:** one tap stops PAA acting; Lockdown keeps only alarms, reminders and SOS.
**Depends on:** T2, T6.
**Steps**
1. `AgentSettings.paused` / `lockdown` toggles in a **"Safety"** card at the top of `MainScreen`.
2. Lockdown ON also: `A11yToggle.setEnabled(false)` (if allowed) and stops `WakeListenerService`; OFF restores
   what was on before (store previous states in prefs).
3. Quick Settings tile `LockdownTileService` ("PAA Lockdown", active state = lockdown on). Register in manifest
   like `PAATileService`.
4. Paused: `Executor` denies everything except SOS (already via Guard rule 2 — SOS is checked first).
**Tests:** covered by `GuardTest`; add a test for the restore-previous-state helper (pure function).
**Verify on phone:** tile on → "message X" is refused with "Lockdown is on"; alarm still rings; tile off → normal.

---

## P1-T13 — Developer-options reminder
**Goal:** bank apps often refuse while Developer options are on; remind the owner to switch them off.
**Steps:** on `MainActivity` resume and once a day (WorkManager periodic, 24 h): if
`Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1` or `ADB_ENABLED == 1` →
low-priority notification "Developer options are on — banking apps may refuse to open. Turn them off when you're
done." (tap → `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`). No auto-changes.
**Verify:** switch Developer options on → notification appears within a minute of opening PAA.

---

## Phase 1 exit check
- `results/phase0.md` filled; P1-T7 gating = pass (or profile split working).
- `tools/eval_chats.py` 15/15 on the emulator; app runs a full day on the phone without hangs or crashes
  (`adb logcat -b crash` empty for PAA).
- Owner told what changed and which settings to keep (Developer options off; screen-reading tile used before banking, or banking in a separate profile).
