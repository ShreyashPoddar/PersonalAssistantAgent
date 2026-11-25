# Banking and PAA on one phone

PAA reads your screen (WhatsApp chats) through Android's accessibility service. Banks and UPI apps don't allow
assistant apps to read their screens, and PAA respects that: it never watches which app is open, never hides
itself, and never changes anything when a banking app starts. You decide when PAA can see the screen.

## Option 1 — separate profile (recommended, least friction)

Keep banking and UPI apps in a **second user profile** where PAA is not installed.

1. Settings → System → Users (on iQOO/vivo: Settings → Users & accounts → Multiple users) → **Add user**.
2. Switch to the new user and install only your banking / UPI apps there.
3. Switch to that user when you bank; switch back afterwards.

Accessibility settings belong to each profile, so PAA in your main profile never touches the banking profile.

## Option 2 — switch screen reading off while you bank

Keep everything in one profile and turn PAA's screen reading off before you open a banking app:

- Quick Settings tile **"PAA screen reading"** (one tap), or
- say **"banking mode"** / **"stop reading my screen"**, or
- the **Screen reading** switch in PAA.

Turn it back on afterwards ("screen reading on"). While it's off, PAA still reads notifications, so most chat tasks
keep working.

## Also

- **Developer options / USB debugging on** make many banking apps refuse to open. PAA reminds you at most once a day
  (you can switch the reminder off in PAA while you develop).
- **Lockdown** (Safety card or the "PAA Lockdown" tile) turns screen reading and "Oyee PA" off in one tap.
- UPI PINs and card details are only ever typed by you. PAA never reads, stores or enters them.
