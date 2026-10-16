# Phase 2 — Communication and money (read-only)

Needs Phase 1 (Executor, Guard, audit, confirm). Order: T1 → T9. Nothing here drives a bank or UPI app; money is
**read** from SMS, notifications and statements the owner already receives.

---

## P2-T1 — Messaging via notification reply (RemoteInput)
**Goal:** reply to a WhatsApp/Telegram chat from its notification, which is the most reliable and least intrusive
send path and needs no accessibility tap.
**Depends on:** P1-T5.
**Read first:** `services/NotificationListener.kt` (it already holds `instance` and reads messaging-style
notifications), `core/messaging/WhatsAppSender.kt`.

**Steps**
1. In `NotificationListener`, keep the most recent `StatusBarNotification` key per chat (map chatKey → sbn key,
   evicted after 30 min). When a notification has a `RemoteInput` action (WhatsApp/Telegram reply action), remember it.
2. `core/messaging/NotificationReplySender.kt` — `fun reply(chatKey, body): Boolean`: find the remembered sbn, find
   the action with `remoteInputs`, build a `RemoteInput` bundle, `action.actionIntent.send(ctx, 0, intent)`.
3. `MessageSkill` (P1-T5): try `NotificationReplySender.reply` first; if there's no live notification for that chat,
   fall back to the existing accessibility auto-send; if neither, return `ActionResult(false, "Open the chat once so I can reply there")`.
4. Telegram: same path (it also posts a reply RemoteInput).
**Tests:** pure `RemoteInputTargetTest` for choosing the reply action from a list of actions (fake action descriptors).
**Verify on phone:** someone messages you; "Oyee PA, reply to <that chat> ok coming" → sent via the notification
(check it appears in WhatsApp). Confirm Guard still asks fingerprint for a first-time contact.

---

## P2-T2 — SMS send + inbox read
**Goal:** send an SMS on command; read incoming SMS (needed for bank alerts in T4).
**Depends on:** P1-T4.
**Steps**
1. Permissions already added for SOS (`SEND_SMS`); add `RECEIVE_SMS`, `READ_SMS` (restricted — "Allow restricted settings").
2. `core/skills/SmsSkill.kt` — `Skill<Action.SendSms>`: `SmsManager.sendMultipartTextMessage`. Resolve the contact
   name→number with `ContactResolver`; `Action.SendSms` carries the number.
3. `LocalTaskParser`: extend `SEND_MESSAGE` so "SMS/text <name> …" sets a channel flag (add `channel: String =
   "whatsapp"` to `LocalParseResult`, value "sms"); the overlay builds `Action.SendSms` vs `Action.SendMessage`.
4. `core/money/SmsInbox.kt` — `fun recent(ctx, sinceMillis): List<RawSms(sender, body, time)>` via
   `content://sms/inbox`. Used by T4 (on demand; no always-on SMS listener needed for history).
5. Optional live: a `BroadcastReceiver` for `SMS_RECEIVED` that passes **bank-sender** messages to the money
   parser (T4). Guard it so only known bank sender IDs are handled; everything else ignored (privacy).
**Tests:** `SmsSkill` number formatting; parser channel selection.
**Verify on phone:** "Oyee PA, text <contact> running late" → confirm → arrives as SMS.

---

## P2-T3 — Email (Gmail)
**Goal:** draft and send email in the owner's style, with preview.
**Depends on:** P1-T4.
**Decision:** start with the **share-intent** route (no OAuth, no new scopes) — reliable and private:
1. `core/skills/EmailSkill.kt` — `Skill<Action.SendEmail>`: build `ACTION_SENDTO` `mailto:` intent with subject/body,
   `setPackage("com.google.android.gm")`; the owner taps Send in Gmail (so "send" is the owner's tap — safe).
   `Guard` still previews the action first.
2. Drafting: the body text comes from the voice/UI command via the local intent model (P5) or Gemini for "write an
   email to X about Y" (cloud, but it's the owner's own request and contains no chat/bank data — still run it
   through `PrivacyGuard`-style check: never include chat/transaction text unless the owner dictated it).
3. Gmail API with OAuth (full auto-send, reading inbox for statements in T6) is a **later** sub-task — only if the
   owner wants auto-send without the Gmail compose screen. Document it but don't build unless asked.
**Verify on phone:** "write an email to <addr> saying I'll join the meeting at 4" → Gmail opens pre-filled → owner sends.

---

## P2-T4 — Bank SMS + UPI notification parser
**Goal:** turn every Axis debit/credit SMS and UPI-app notification into a structured transaction, on the phone.
**Depends on:** P2-T2.
**Read first:** `services/NotificationListener.kt`, `data/db/AppDatabase.kt`.
**Privacy:** bank SMS/notification text is as sensitive as chats — same rules (never logged, never sent out).

**Steps**
1. `data/models/TransactionEntity.kt`
   ```kotlin
   @Entity(tableName = "transactions")
   data class TransactionEntity(
       @PrimaryKey val id: String,          // hash(source + rawTime + amount + ref) — dedupe SMS vs notification
       val at: Long, val amountPaise: Long, val direction: String,  // DEBIT | CREDIT
       val channel: String,                 // UPI | CARD | NEFT | ATM | OTHER
       val counterparty: String?,           // "SWIGGY", a VPA, a name — as the message gives it
       val refId: String?, val balancePaise: Long?,
       val bank: String,                    // "AXIS"
       val category: String? = null,        // filled by T5
       val note: String? = null, val source: String  // "SMS" | "NOTIF"
   )
   ```
2. `data/db/MoneyDao.kt` (insert ignore-on-conflict, observe range, update category/note, sums by category). Add
   entity + `version = 7` + `MIGRATION_6_7` + DAO in DI.
3. `core/money/BankSmsParser.kt` — **pure Kotlin**, the heart of this task. Input a `RawSms`/notification text +
   sender; output `TransactionEntity?`. Handle Axis formats (and generic UPI). Use robust regexes for:
   - amount: `(?:INR|Rs\.?|₹)\s?([\d,]+\.?\d*)`
   - direction: `debited|spent|withdrawn|paid` → DEBIT; `credited|received|deposited` → CREDIT
   - channel: contains `UPI`/`VPA`/`@` → UPI; `card`→CARD; `NEFT|IMPS|RTGS`→NEFT; `ATM`→ATM
   - counterparty: after `to `/`at `/`from `/`VPA ` up to a delimiter; VPA pattern `[\w.\-]+@[\w]+`
   - balance: `(?:Avl|Available) (?:Bal|Balance)[:\s]*(?:INR|Rs\.?|₹)?\s?([\d,]+\.?\d*)`
   - refId: `(?:Ref|RRN|UPI Ref|txn)[:\s#]*([\dA-Za-z]+)`
   Keep a list of Axis sender IDs (`AxisBk`, `AXISBK`, etc.) and UPI app packages for notifications.
4. `core/money/MoneyIntake.kt` (`@Singleton`) — called from the SMS receiver (T2) and from `NotificationListener`
   when the package is a bank/UPI app: parse → insert (dedupe by id). On first run, backfill from `SmsInbox.recent`
   (last 180 days) in a `WorkManager` job.
5. **Test 14** harness: a debug screen "Parse my bank SMS" that runs the parser over the last N bank SMS **on the
   phone** and shows counts of parsed/failed by field — never leaves the phone, text not logged.
**Tests:** `BankSmsParserTest` with **synthetic** Axis-style messages (invented amounts/VPAs) covering debit, credit,
UPI, card, ATM, NEFT, balance present/absent, Hinglish merchant names, and a non-transaction promo SMS → null.
**Verify on phone (Test 14):** owner runs the debug screen; ≥ 90% of real Axis alerts parse amount+direction+
counterparty. Failures: owner pastes the *format* (not real data) so the agent adds a synthetic test + regex.

---

## P2-T5 — Expense categoriser
**Goal:** label each transaction (Food, Groceries, Travel, Shopping, Bills, Rent, Transfer-to-friend, Income, …)
and let the owner correct it; corrections stick.
**Depends on:** T4.
**Steps**
1. `core/money/Categorizer.kt` — pure: `fun categorize(txn, learned: Map<String,String>): String`. First check
   `learned` (counterparty → category the owner set). Else keyword rules on counterparty (zomato/swiggy/blinkit→Food
   & groceries; irctc/uber/ola/redbus→Travel; amazon/flipkart/myntra→Shopping; electricity/recharge/jio/airtel→Bills;
   a person's name or VPA with no merkle match → Transfer). Else "Uncategorised".
2. `data/models/MerchantRuleEntity.kt` (counterparty→category, set by owner) + DAO + migration `version = 8`.
3. Run the categoriser when a transaction is inserted and when a rule changes (re-categorise matching rows).
4. UI "Money" screen: list by month, each row editable (tap → pick category → saves a MerchantRule). Totals per
   category, a simple bar list (follow `dataviz` skill for colors if adding a chart).
**Tests:** `CategorizerTest` — learned overrides rules; each keyword family; unknown → Uncategorised.

---

## P2-T6 — Statement PDF import (optional, higher detail)
**Goal:** import Axis e-statement PDFs for complete history (SMS can miss some).
**Depends on:** T4.
**Steps**
1. Owner shares the statement PDF to PAA (the app already has a SEND share-intent — `ShareIntakeActivity`).
2. `core/money/StatementImporter.kt` — open with a password the owner types once (stored in Keystore, not logged).
   Axis format noted in research: first 4 letters of name (UPPERCASE) + DDMM, or + 9-digit customer ID. Ask the
   owner for their exact format; don't hardcode personal data.
3. Parse the table (use a maintained PDF lib already acceptable to the project; if none, fall back to `PdfRenderer`
   + text extraction is hard — prefer a text-based PDF parser). Map rows to `TransactionEntity` (source "STATEMENT"),
   dedupe against SMS-sourced rows by date+amount+ref.
**Note:** if reliable PDF table parsing proves heavy, keep this optional; SMS alerts already cover daily use.
**Verify on phone:** owner imports one statement; rows merge without duplicating the SMS ones.

---

## P2-T7 — IOU ledger (who owes whom), from chats + transactions
**Goal:** track money owed, linking a chat promise ("I'll pay you back for dinner") to a transaction.
**Depends on:** T4, and the chat pipeline (existing).
**Steps**
1. `data/models/LedgerEntryEntity.kt` (person, amountPaise, direction OWED_TO_ME/I_OWE, reason, sourceChat,
   createdAt, settledAt?) + DAO + migration `version = 9`.
2. Detection is a **task-like** signal from the chat model (P5 adds an intent "money owed"), or a manual add in UI,
   or the owner saying "note that Rahul owes me 500". Creating a ledger entry is PRIVATE_REVERSIBLE (no confirm).
3. Settle: when a matching transfer transaction appears (same person/amount, ± few days) suggest "Mark settled?"
   (a notification, owner taps). Never auto-settle.
4. UI "Owed" section in the Money screen.
**Tests:** `LedgerMatchTest` — a CREDIT from Rahul ₹500 within 7 days suggests settling his ₹500 OWED_TO_ME.

---

## P2-T8 — "Ask anything about my money" (local Q&A)
**Goal:** "how much did I spend on food this month", "did Rahul pay me back", "what was my biggest expense in July"
answered on the phone.
**Depends on:** T5, T7.
**Steps**
1. `core/money/MoneyQuery.kt` — pure functions over the transaction list: totalByCategory(range), biggest(range),
   spendTrend, owedSummary. A small deterministic parser maps the question to one of these (month names, "this
   month", category words, person names) — no cloud.
2. Hook into the voice/overlay flow: if the utterance is money-related (keywords) and local, answer from
   `MoneyQuery`; never send transaction data to the cloud.
**Tests:** `MoneyQueryTest` over a synthetic transaction set.
**Verify on phone:** ask three questions; answers match the Money screen.

---

## P2-T9 — Privacy audit for money + comms
**Goal:** prove nothing private leaks.
**Steps**
1. Extend `PrivacyGuard` / add `MoneyPrivacyTest` (like the existing `PrivacyBoundaryTest`): transaction text and
   SMS bodies are never included in any cloud request builder; `DetectionLog` entries for money contain only
   type/count/category, never amounts or counterparties.
2. Grep gate in CI-style check (document it): `grep -rn "amountPaise\|counterparty" core/ai/` must return nothing.
**Exit Phase 2:** build+tests green; Test 14 ≥ 90%; Money screen shows correct categories on the phone; owner can
ask money questions offline; privacy tests pass.
