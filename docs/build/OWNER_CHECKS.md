# Checks only the owner can do (phone)

Everything below is built, unit-tested and installed; each needs a person (fingerprint, a real SMS, a decision, or
a stopwatch). Tick them in `STATUS.md` when done.

| # | What | How (≈ time) |
|---|---|---|
| 1 | **Screen reading is OFF** right now (only Grammarly's accessibility is on) | If you didn't turn it off on purpose: Quick Settings tile **PAA screen reading** or say "screen reading on". (1 min) |
| 2 | P1-T7 banking: choose a path | Main screen → Screen reading card → "How to keep banking separate". Either a second user profile, or the tile/"banking mode" before banking. (decision) |
| 3 | P1-T8 app launcher speed | Say "Oyee PA, open YouTube"; time from end of speech to YouTube showing. Target < 1.5 s. (1 min) |
| 4 | P1-T9 SOS | SOS card → **Set up**: people to alert, call target, your own number, allow SMS/location/phone. Then **Run a drill** — only your own number gets a "TEST — ignore" SMS with a maps link. Tap "I'm safe" in the notification (fingerprint/PIN). Also set up the phone's own Emergency SOS. (5 min) |
| 5 | P1-T11 WhatsApp reading still works | Have someone send you "kal 5 baje tak notes bhej dena" while the chat is open → a task appears. (2 min) |
| 6 | P1-T12 Lockdown | Safety card → Lockdown on → "message <someone> test" is refused, an alarm still rings; Lockdown off → screen reading / Oyee PA come back as before. (3 min) |
| 7 | Alarms switch | Alarms card → off → nothing rings; on → alarms back for future tasks. (2 min) |
| 8 | P2-T4 money (Test 14) | Money card → **Import bank SMS** (allow SMS). Check that this month's payments look right (amounts, payees). Fix any category by tapping it — it sticks for that payee. If a bank SMS was missed, tell Claude the *format* (not the real numbers). (5 min) |
| 9 | P2 money voice | Say "how much did I spend this month", "Rahul owes me 500", "who owes me money". (2 min) |
| 10 | P2-T2/T3 SMS + email | "sms <contact> that test" → preview → Send → fingerprint first time → SMS arrives. "write an email to <you>@gmail.com saying test" → Gmail opens filled in → you tap Send. (3 min) |
| 11 | P4-T1 hub | On the laptop: `python hub/server.py` (allow it through Windows Firewall when asked), copy the pairing line → phone Hub card → paste → Pair → Check. (3 min) |
| 12 | Developer-options reminder | It reminds at most once a day while Developer options are on; switch it off in the Screen reading card while you're developing. |
| 13 | Voice lock | Enrol "Only my voice" (8 sentences + someone else's 2–3 tries), then "Test a voice" for you and someone else; tell Claude the scores. (10 min) |
