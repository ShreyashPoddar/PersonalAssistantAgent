# Target architecture (what the code should look like when the plan is done)

Existing code stays where it is (`services/`, `core/router/`, `core/ai/`, `data/`). New code goes into the
packages below. Every capability is a **Skill** that executes a typed **Action**; nothing performs an outward or
money action except through `Executor`, and `Executor` only runs what `Guard` approves.

```
app/src/main/java/com/paa/assistant/
├── core/agent/            ← P1: the action framework
│   ├── Action.kt          typed actions (sealed class) + RiskTier
│   ├── CommandSource.kt   who asked: OWNER_VOICE, OWNER_UI, SCHEDULE, SYSTEM_EVENT
│   ├── Guard.kt           policy: allowed? needs confirm? lockdown?
│   ├── BiometricGate.kt   fingerprint confirm bound to a Keystore key (CryptoObject)
│   ├── ConfirmActivity.kt shows the typed action, asks for fingerprint
│   ├── Executor.kt        Action → Skill, writes the audit log
│   ├── Skill.kt           interface Skill<A : Action>
│   └── AgentSettings.kt   kill switch, Lockdown, per-contact rules
├── core/shield/           ← P1: owner-controlled screen-reading switch (NOT bank-app detection)
│   └── A11yToggle.kt      turns PAA's accessibility service off/on when the OWNER asks (tile/switch/voice)
├── core/skills/           ← P1–P3: one file per skill
│   ├── OpenAppSkill.kt  AppDirectory.kt
│   ├── MessageSkill.kt  SmsSkill.kt  EmailSkill.kt  CallSkill.kt
│   └── OrderSkill.kt  TravelSkill.kt
├── core/sos/              ← P1: SosManager, SosService, SosSettings
├── core/money/            ← P2: bank SMS parser, UPI notification parser, statements, categoriser, ledger, Q&A
├── core/flows/            ← P3: screen-flow engine (scripted app automation) + flow files in assets/flows/
├── core/mcp/              ← P3: MCP client (Swiggy / Zomato)
├── core/hub/              ← P4: phone ↔ home-hub link
├── core/security/         ← P1: text sanitiser, isolated parse service, Keystore helpers
└── data/models + data/db  ← new entities: AuditEntry, ContactRule, Transaction, LedgerEntry, Category rule
hub/                        ← P4: Python code for the home hub (Raspberry Pi / laptop)
```

## Core types (P1 creates these; later phases only add Action subclasses and Skills)

```kotlin
// core/agent/Action.kt
enum class RiskTier { READ, PRIVATE_REVERSIBLE, OUTWARD, MONEY, SOS }

sealed class Action(val tier: RiskTier) {
    /** One line shown to the owner on the confirm screen, built from typed fields only (never model text). */
    abstract fun summary(): String

    data class OpenApp(val packageName: String, val label: String) : Action(RiskTier.READ) {
        override fun summary() = "Open $label"
    }
    data class SetAlarm(val atMillis: Long, val label: String) : Action(RiskTier.PRIVATE_REVERSIBLE) {
        override fun summary() = "Alarm \"$label\" at ${fmt(atMillis)}"
    }
    data class SendMessage(val app: String, val contactId: String, val contactName: String, val body: String) :
        Action(RiskTier.OUTWARD) { override fun summary() = "$app to $contactName: \"$body\"" }
    data class SendSms(val number: String, val contactName: String, val body: String) : Action(RiskTier.OUTWARD) {
        override fun summary() = "SMS to $contactName: \"$body\""
    }
    data class SendEmail(val to: String, val subject: String, val body: String) : Action(RiskTier.OUTWARD) {
        override fun summary() = "Email to $to: \"$subject\""
    }
    data class PlaceCall(val number: String, val contactName: String) : Action(RiskTier.OUTWARD) {
        override fun summary() = "Call $contactName"
    }
    data class PlaceOrder(val app: String, val cartSummary: String, val amountPaise: Long) : Action(RiskTier.MONEY) {
        override fun summary() = "Order on $app: $cartSummary — ₹${amountPaise / 100.0}"
    }
    data class Sos(val reason: String) : Action(RiskTier.SOS) { override fun summary() = "SOS" }
    // later phases add more subclasses here
}

// core/agent/CommandSource.kt
enum class CommandSource { OWNER_VOICE, OWNER_UI, SCHEDULE, SYSTEM_EVENT }
// SYSTEM_EVENT = something PAA noticed (a chat message, an SMS). It may only lead to READ / PRIVATE_REVERSIBLE
// actions on its own; anything above needs the owner to confirm.

// core/agent/Guard.kt
sealed class Verdict {
    object Allow : Verdict()
    data class Confirm(val biometric: Boolean) : Verdict()   // show ConfirmActivity
    data class Deny(val reason: String) : Verdict()
}
@Singleton class Guard @Inject constructor(private val settings: AgentSettings, private val rules: ContactRuleDao) {
    suspend fun check(action: Action, source: CommandSource): Verdict { /* P1-T2 */ }
}

// core/agent/Skill.kt
interface Skill<A : Action> { suspend fun run(action: A): ActionResult }
data class ActionResult(val ok: Boolean, val message: String)

// core/agent/Executor.kt
@Singleton class Executor @Inject constructor(guard: Guard, audit: AuditDao, skills: SkillRegistry) {
    /** The ONLY entry point for doing things. Returns the result or why it didn't run. */
    suspend fun submit(action: Action, source: CommandSource): ActionResult
}
```

## Flow of one command
```
owner says "Oyee PA, message papa I'll be late"
 → WakeListenerService → VoiceOverlayViewModel.processCommand
 → IntentRouter / LocalTaskParser (or intent model, P5) → Action.SendMessage(...)
 → Executor.submit(action, OWNER_VOICE)
     → Guard.check: OUTWARD + contact rule for papa = PREVIEW → Verdict.Confirm(biometric = false)
     → ConfirmActivity shows action.summary() → owner taps Send
     → MessageSkill.run → notification reply or accessibility auto-send
     → AuditDao.insert(entry) → result shown in the overlay
```

## Flow of something PAA notices
```
WhatsApp notification "bhai 500 bhej de kal tak"
 → NotificationListener → (P1-T10 sanitiser) → ChatMessageProcessor → task "Send ₹500 to Karan" scheduled
   (PRIVATE_REVERSIBLE, allowed). It does NOT create a payment action: money only from the owner's command.
```

## Process layout (manifest)
| Process | Contains | Network | Why |
|---|---|---|---|
| main | UI, services, Executor, skills | yes | today's app |
| `:parse` (`isolatedProcess="true"`) | `SanitizeService` (P1-T10) | **no** | untrusted text parsed with no permissions (Android's BlastDoor) |
| later `:brain` (P5) | on-device models | no | a model crash or memory spike can't take the UI down |
