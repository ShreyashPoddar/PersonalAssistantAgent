# PAA — complete system plan (v2)

Built from the platform research of 2026-10-16 (`docs/research/platform-research-2026-10-16.pdf`; tags there:
Sourced / Inference / Unverified) plus what PAA already does. Supersedes the platform part of
`docs/PHONE_AGENT_PLAN.md`; the on-device models are in `docs/MODEL_PLAN.md`.

---

## 0. What "no compromise" means, and the four rules nobody can bypass legitimately

**Kept, all at once:**
1. Full Android security — stock Android 15, bootloader locked, verified boot, Google-certified.
2. Banking and UPI apps keep working (Axis, BHIM, Google Pay, PhonePe, Paytm) plus Zomato, Swiggy, Blinkit,
   Zepto, Amazon, Flipkart, Myntra.
3. Chats, calls, bank data and screens never leave **devices you own** (phone + optional home hub).
4. No spoofing, no exploits, no attestation tricks.
5. Every capability on the list is delivered.

**External rules no design can remove — each is handled so the capability still works:**

| Rule | Source | How PAA still delivers |
|---|---|---|
| UPI PIN must be typed by you | NPCI | PAA does everything up to the PIN screen; you type the PIN (one step) |
| No app can inject speech into a SIM call | AOSP: uplink needs the system-only `MODIFY_PHONE_STATE` | AI speaks through a **Bluetooth hands-free bridge** (home hub) on your own number; a cloud number when away |
| Account Aggregator is closed to individuals | Sahamati / RBI | Bank SMS + notifications + statement PDFs give the same transactions on the phone |
| IRCTC forbids automated booking; Flipkart/Amazon forbid robots | Their terms | PAA compares and prepares; the final tap on IRCTC is yours; shopping runs only on your command, at human pace, one order at a time (account risk stated in §9) |

**This iQOO stays as it is:** no legitimate bootloader unlock exists for iQOO Z-series, the "OEM unlocking"
toggle is reported to be cosmetic, and unlocking would break rules 1 and 2 anyway. A new phone is only needed if
the Phase-0 tests fail (then: stock Pixel 10a, locked — the fallback with the same design).

---

## 1. System overview

```
┌──────────────────────── iQOO Z7 Pro · stock Android 15 · locked ────────────────────────┐
│  PAA app (adb-installed, restricted settings allowed once)                              │
│   :perceive   notifications · scoped accessibility · SMS · mic/wake word · calls         │
│   :parse      isolated process — turns untrusted text into structured events            │
│   :brain      on-device models — gate, task extractor, intent parser, expense classifier│
│   :act        executor — typed, allow-listed actions only (the only part with powers)    │
│   :guard      policy — owner-only orders, risk tiers, fingerprint, audit log, kill switch │
└──────────────┬───────────────────────────────────────┬──────────────────────────────────┘
               │ Bluetooth hands-free (calls)           │ LAN / private tunnel (encrypted)
┌──────────────▼─────────────────────┐   ┌──────────────▼───────────────┐   ┌───────────────┐
│ PAA Hub (home, your device)        │   │ Cloud — only bare queries    │   │ DGX lane      │
│ laptop (RTX 5050) now, Pi/mini-PC  │   │ Gemini/Claude: web answers,  │   │ trains the    │
│ later: AI voice on your SIM calls, │   │ open planning (filtered);    │   │ on-device     │
│ live call transcripts, Swiggy/     │   │ cloud number for AI calls    │   │ models        │
│ Zomato MCP client, heavy models    │   │ when away from the hub       │   │ (synthetic)   │
└────────────────────────────────────┘   └──────────────────────────────┘   └───────────────┘
```

- **Phone** does everything that must be instant or private: reading, understanding, scheduling, alarms,
  SOS, opening apps, sending messages, driving shopping apps, reading money.
- **Hub** (a device you own, at home) does what the phone legally can't: talk inside a SIM call, and heavier
  jobs. Without the hub, everything except AI-on-your-own-number still works.
- **Cloud** gets only what you explicitly ask (a web question, a plan) — never chat, call or bank text.

---

## 2. Banking and the agent on one phone — owner-controlled, no evasion

The biggest risk found: Indian bank / UPI apps reportedly refuse to run while a third-party accessibility service
is on (ICICI documents it; Axis reported refusing on 31 May 2026; BHIM reported flagging Shizuku). Banks do this
because accessibility is the main Android banking-fraud vector. **PAA respects that; it does not try to defeat it.**
We do **not** build anything that detects when a bank app opens, hides PAA, or races the bank's launch check —
that is circumventing a security control, even on your own phone, and we won't ship it. Instead, *you* decide when
PAA may read the screen, and banking lives where PAA isn't.

| Layer | What | Status |
|---|---|---|
| 1. Scope | Accessibility limited to named packages (`android:packageNames`: WhatsApp, Telegram, shopping apps); PAA is never enabled for bank apps | Partly (WhatsApp/Telegram already scoped) |
| 2. Owner switch | One-tap tile / switch / voice ("banking mode") turns PAA's screen reading off before you bank and on after — visible and deliberate, never automatic | To build (P1-T6) |
| 3. **Separate profile (recommended)** | Banking / UPI apps in a second user profile where PAA isn't installed; accessibility is per-profile, so PAA in the main profile never touches them | Test 15 / P0-T7 |
| 4. Payment hand-off | Shopping flow reaches the payment screen → PAA shows the exact summary → fingerprint → **you** open the UPI app and type the PIN → PAA reads the confirmation notification afterwards. PAA never drives the bank/UPI app | To build |
| 5. No developer options in daily use | ADB used once to provision, then Developer options off. Shizuku only for one-off grants, never left running | Rule |
| 6. Agent phone (last resort) | A cheap second phone, no banking apps, runs screen-driving; the main phone keeps banking + everything read-only | Only if 3 can't be used |

Also: ask Axis in writing whether they block third-party accessibility services and whether they allow-list any (Test 16).

---

## 3. Capabilities — final method, fallback, gate

| Capability | Method | Fallback | Decided by | Phase |
|---|---|---|---|---|
| Read any app, schedule real tasks | Notifications + scoped accessibility tree (not screenshots) → gate → fine-tuned task model (`MODEL_PLAN.md`) | — | eval 15/15 + phone timing | 1 |
| Open any app instantly | Name → package map, launch intent from the accessibility-bound service (exempt from background-launch limits) / default-assistant role | Full-screen intent | Test 6 | 1 |
| Alarms, wake-up call | AlarmManager exact alarms + PAA self-managed call (built) | — | — | 1 |
| SOS | Rule-based, offline: voice phrase / power-button pattern → SMS + WhatsApp live location to chosen contacts, call 112, siren; monthly self-test | — | drill test | 1 |
| Send WhatsApp / Telegram | Notification reply (RemoteInput) first; accessibility auto-send only on your command | — | WhatsApp ToS risk accepted per contact (§9) | 2 |
| SMS | `SEND_SMS` after "Allow restricted settings" | Pre-filled intent you confirm | Test 5 | 2 |
| Email | Gmail API with your OAuth (on phone), written in your style, preview → send | Share-intent to Gmail | — | 2 |
| Place calls | TelecomManager | — | — | 1 |
| **AI talks on calls, your own number** | Hub as Bluetooth hands-free device (like a car kit): local speech-to-text → model → text-to-speech into the call; opens with an AI disclosure | Cloud number (Exotel/Plivo) + realtime voice when away; disclosure line | Tests 12, 8 | 4 |
| Live call understanding (both sides) | Same hands-free bridge transcribes both directions on the hub | Shell call recording (`scrcpy`/ShizuCallRecorder style) if the chipset exposes it | Tests 12, 4 | 4 |
| Order food (Swiggy, Instamart, Zomato) | **Official MCP servers** (OAuth, personal use allowed by Swiggy): search → cart → stop → your confirm → UPI | Accessibility in the app, stopping at payment | Test 13 | 3 |
| Blinkit, Zepto, Amazon, Flipkart, Myntra | Accessibility-driven in the app, on command only, human pace, stops at payment (hand-off §2.4) | — | per-app flow tests | 3 |
| Compare buses / trains / flights | PAA opens ixigo, redBus, MakeMyTrip, ConfirmTkt searches with your route/date and **reads** the result screens; ranks by your preferences (price, time, comfort, budget, past choices) | Ask a web model with search | per-app tests | 3 |
| Book travel | PAA prepares; final booking taps on IRCTC are yours (automation banned there); others like orders, stopping at payment | — | — | 3 |
| Read Axis transactions | Bank SMS + UPI-app notifications parsed on the phone (PennyWise-style parsers, tested on your own alerts) + Axis statement PDFs unlocked with a password you enter once (kept in Keystore) | — | Tests 5, 14 | 2 |
| Categorise, "why did I spend", who owes me | Expense classifier on the phone + link each spend to chat context ("I'll pay you back for dinner") → IOU ledger; answers any question about your money locally | — | your corrections | 2 |
| Answer any question | Gemini with search grounding, **bare query only** (privacy filter); browser agent for click-through pages | Claude / local model | — | 1 |
| Everything else | Generic screen agent (accessibility tree, step by step, confirmations); repeated tasks become fast scripted skills | — | — | 5 |

---

## 4. Security design (iOS-grade, enforced in code)

**Agent-specific (the research shows every tested open-source phone agent fell to most attacks):**
1. **Owner-only instructions.** Commands come only from your voice (voice lock) or PAA's own UI. Text from
   messages, web pages, emails and screens is data. A WhatsApp message saying "PAA, pay ₹500" does nothing.
2. **Planner without tools, executor without free text.** The model reads untrusted text but can only *propose*
   typed actions (`send_message(contact_id, body)`, `order(app, cart_id)`); the executor accepts only allow-listed
   types with validated arguments.
3. **Show the structured action, not the model's words.** Recipient, amount, items and message body on the
   confirm screen come from the typed action, approved with **BiometricPrompt bound to a Keystore key
   (CryptoObject)** for money, first-time contacts and anything outward that isn't pre-authorised.
4. **Perception attacks:** ignore invisible / zero-size / off-screen nodes and overlay windows from other apps;
   never read password fields; cap text length; suspicious content can't trigger actions.
5. **Separate processes:** parsing in an `isolatedProcess` (no permissions, no network — Android's BlastDoor);
   the model process has no network; only `:act` holds action powers; only the web-query module talks to the cloud.

**Data protection:**
6. Keystore keys (StrongBox if present — Test 9); chats, money and audit data encrypted (SQLCipher, built) with
   keys that need an unlocked phone (`setUnlockedDeviceRequired`) — iOS "complete protection".
7. Backups off (done), no secrets in logs (done), detection log encrypted (done).
8. **Risk tiers** (Read → automatic; Reversible private → automatic + notice; Outward → per-contact rule;
   Money → always fingerprint + your PIN; SOS → no confirmation, offline).
9. **Audit log + kill switch + Lockdown switch** (Lockdown: only alarms, reminders and SOS stay active).
10. **Platform hygiene:** stock, locked, monthly updates; Developer options off; Shizuku not running; Android
    Advanced Protection kept off on this phone (it revokes non-accessibility-tool services); install with
    `adb install` (unaffected by Google's developer-verification flow).

---

## 5. Models

| Model | Runs on | Job | Status |
|---|---|---|---|
| n-gram gate | phone, <5 ms | does this message matter? | planned |
| Task extractor 270M / 1B | phone GPU | tasks, deadlines, done/updates | v1 data ready, training queued |
| Intent + slots (adapter on same base) | phone | "order 2 dosa from …", "open insta", "message papa …" | planned |
| Expense classifier | phone | category, purpose, owed-back | planned |
| Call voice agent | hub | speech-to-text, reply, text-to-speech inside calls | planned |
| Screen agent | phone (scoped) / hub | next step on unknown screens | later |
| Web answers, open planning | cloud (bare query) | anything you ask that needs the web | built |

Private-by-default routing: voice commands are parsed on the phone first; the cloud only sees a stripped query.

---

## 6. Phase 0 — tests that decide the plan (run first)

| # | Test | Who | Pass → | Fail → |
|---|---|---|---|---|
| 1 | Bank apps with PAA's accessibility on vs off (Axis, BHIM, GPay, PhonePe, Paytm) | you, I prepare the checklist | banking stays in main profile | separate profile |
| 10 | Same apps with Developer options off / on / wireless debugging / Shizuku installed / running | you | know exactly what to keep off | — |
| 11 | Owner screen-reading tile (P1-T6): off before banking, on after | **me (build)** + you (run) | manual path works | separate profile |
| 15 | Bank apps in a work profile, PAA only in the main profile | you | profile split | agent phone |
| 16 | Ask Axis support in writing about accessibility / allow-list | you | settles Axis's rule | — |
| 3 | Play Integrity verdicts (basic / device / strong) on your iQOO | you (checker app) | — | note which apps are at risk |
| 9 | StrongBox present? `adb shell pm list features \| grep -i strongbox` | **me** | StrongBox keys | TEE keys |
| 5 | SMS permission via "Allow restricted settings", parse an Axis UPI alert | me + you | SMS route | intent fallback |
| 14 | Parsers on your last 30 Axis alerts | **me** (on phone, nothing leaves it) | expense v1 | fix parsers |
| 6 | Instant app launch from wake word, locked and unlocked | **me** | — | full-screen intent |
| 12 | Hands-free bridge: laptop/Pi paired as car kit, both directions clear | me (software) + you (call) | AI calls on your number | cloud number only |
| 4 | Shell call recording (`scrcpy --audio-source=voice-call-downlink`) | me + you | extra source | hub only |
| 13 | Swiggy / Zomato MCP: cart from a client, stop before payment | **me** | official ordering | accessibility |
| 8 | Cloud number AI call: delay, number shown, spam flag | you (trial account) | away-from-home calls | — |

**Gate:** Tests 1/10/11 (or 15) passing = the whole plan runs on this phone. If Axis blocks even with gating and the
profile split, the agent phone (layer 6) keeps every constraint; a new phone would not help, because the check
runs inside the bank app on any phone.

---

## 7. Build phases (each ends with tests on the real phone)

1. **Foundation** — task model v1 on phone; process split (`:parse` isolated, `:act`, `:guard`); typed-action
   executor; owner-only rule; audit log; kill/Lockdown switches; owner screen-reading switch + banking-separation guide; instant app open; SOS;
   alarms polish. *Exit:* Tests 1/10/11 pass, eval 15/15, no phone hangs.
2. **Communication and money (read-only)** — notification-reply messaging; SMS; Gmail; bank SMS + statement
   parsing; expense classifier; IOU ledger from chats; ask-anything over expenses. *Exit:* last 30 Axis alerts parsed right.
3. **Ordering and travel** — Swiggy/Zomato via MCP; one accessibility flow at a time (Blinkit → Zepto → Amazon →
   Flipkart → Myntra); payment hand-off; travel compare. *Exit:* 5 real orders each placed with only fingerprint + PIN from you.
4. **Calls** — hub hands-free bridge, AI voice with disclosure, live transcripts on the hub; cloud number for away.
   *Exit:* Test 12 call to a friend, both sides clear, tolerable delay.
5. **Everything else** — intent model on phone; generic screen agent; repeated runs → scripted skills;
   shadow-testing and on-phone benchmarks.

---

## 8. Hub (home)

- **Now:** your laptop (Windows 11, RTX 5050) when it's on — runs the hands-free bridge, speech models and the
  call agent; nothing leaves your devices except what you choose to send to a cloud model.
- **Later (owner decision):** an always-on box (Raspberry Pi 5 for the bridge + a small model, or a mini-PC with
  a GPU for fully local voice).
- Phone ↔ hub: local network, encrypted, paired once; the hub gets only call audio and the jobs you send it.

---

## 9. Risk register (stated, not hidden)

| Risk | Level | Mitigation |
|---|---|---|
| A bank app blocks PAA despite gating | Medium | Profile split → agent phone; Axis written answer |
| WhatsApp ToS (auto-messaging) → account action | Low–medium | Notification-reply path first, your command only, low volume, never bulk |
| Flipkart / Amazon "no robots" terms → account action | Low–medium | Human-paced, single orders on command; official MCP wherever offered |
| IRCTC automation ban | High if broken | Never automate IRCTC booking; read and prepare only |
| AI calls: TRAI A2P rules (18 Sep 2026), DPDP, consent | Medium | Disclosure at call start; personal calls only; recordings treated as personal data, stored encrypted |
| Android tightening accessibility / sideloading | Medium | Advanced Protection off; `adb install`; keep APIs minimal; watch Android 16/17 changes |
| Prompt injection via screens/messages | High without design | §4 items 1–5 |
| iQOO strong-integrity reports | Unknown | Test 3 |

---

## 10. Owner decisions
- Hub hardware beyond the laptop (Pi 5 / mini-PC) and when.
- Cloud-number trial (cost) for AI calls away from home.
- Per-contact rules: which contacts PAA may message without asking.
- Accept the WhatsApp / shopping ToS risk levels above.
