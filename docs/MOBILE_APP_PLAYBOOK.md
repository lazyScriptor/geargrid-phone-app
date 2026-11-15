# Mobile App Playbook

**How to add a companion phone app to a business system, using AI coding agents. Written from the GearGrid Phone build.**

---

## 1. What this is

This is a guide for you, the owner, to reuse in any future project. We built an Android companion app for the GearGrid shop system, which handles equipment rental in Sri Lanka. It took many AI agents, many mistakes and a lot of rework. This playbook keeps what worked and lists what went wrong, so the next app (a shop system, a booking app, a delivery app) goes faster and with fewer surprises.

Plain words used here:

- **Backend / server:** the computer in the cloud that holds your data.
- **Push notification:** a message the server sends to a phone, even when the app is closed.
- **Push token:** the phone app's mailing address for notifications.
- **Pairing:** linking one phone to one shop using a short code.
- **Build:** turning the code into an app file you can install.
- **Branch:** a separate copy of the code where work happens, so the main code stays safe.
- **Commit:** a saved checkpoint of the code.
- **Feature switch:** an on/off switch that lets you ship code without turning it on for anyone.

---

## 2. The flow that worked

Follow these steps in order. Skipping one is where most of our trouble came from.

### Step 1. Write the spec first

A spec is a written plan in plain language: what the app does, who uses it, and what it must never do. Ask the AI to write it, then read it yourself. Do not let anyone build until you have read it.

### Step 2. Confirm the decisions with the owner

The spec will contain choices only you can make. Examples: who sees money amounts, which contacts are shared, what happens when a phone is lost. The AI must list these as questions and wait for your answers. Section 7 has our list. Every time we skipped this, we rebuilt something later.

### Step 3. Build in slices

A slice is a small piece that works from start to finish. For example: "pair a phone, and see one notification." Do not build all screens first and connect them later. Each slice should be something you can test on a real phone before the next one begins.

### Step 4. One branch per release

Give each release its own named branch (for example, `release-1.2`). All agents work on that one branch. Merge pieces in order. This stops two agents from working on different, conflicting versions of the code.

### Step 5. Automatic checks on every change

Set up a robot that checks every saved change. It should run the tests, check the types of data, and check that every text exists in every language. A robot never gets tired and never forgets. Make sure it covers all your files. One of our bugs got through because the checker did not look at some files.

### Step 6. Build the phone app in the cloud from day one

Do not wait. On day one, set up a cloud build that makes the app file from the code on every change. Our early app code was written by several agents who never once built it. The first real build then failed on a missing icon, a wrong table name and a wrong variable. Building early turns surprises into small fixes.

### Step 7. A careful deploy

Deploy means putting the new version where real people use it. Always in this order:

1. **Backup.** Save a copy of the database first.
2. **Server.** Update the server.
3. **Website.** Update the web part.
4. **Phone app.** Release the app last.

Also:

- **Put new features behind an on/off switch**, one per shop. Ship it off. Turn it on for one friendly shop. Then turn it on for others.
- **Only add to the database, never delete.** A change that only adds can be undone safely.
- **Keep older phone apps working.** Not everyone updates on day one.
- **Write a rollback plan** before you start: how to go back, in what order, and who does it.
- **Restart at quiet times.** Our server took about two minutes to come back after each restart, because of startup checks. Users saw errors during that time.

### Step 8. Test on real phones

AI agents cannot hold a phone. They cannot see how a Samsung or Vivo phone behaves. You or your staff must test on at least two or three real, different phones before each release. Keep a written test list (Section 6 has one).

### Step 9. Keep a "settings to make adjustable later" list

While building, many numbers get fixed in the code: how long to keep data, how many digits to match, quiet hours. Do not stop to build a settings screen for each one. Write each on a list, with a short note in the code. When the app is stable, pick the ones worth turning into real settings. This keeps the first release small without losing good ideas.

---

## 3. Common features worth having in any companion phone app

These are useful in almost any business app. Pick what fits.

| Feature | Why it matters |
| :--- | :--- |
| **Pair a phone with a short code** shown on the desktop; each phone gets its own key | Easy for staff, and one lost phone does not expose the others. |
| **Admin control of phones:** approve, suspend, revoke, remote wipe. The owner's own phone is approved automatically. | You decide who has access, and can cut access in seconds. |
| **Audit log** of who did what | When something goes wrong, you can see what happened and who did it. |
| **Push notifications** for key events (new bill, payment, return, deletion, blacklisting) | You know what is happening without opening anything. |
| **Notification controls:** per-shop and per-person on/off, minimum amounts, quiet hours, daily digest, per-phone mute | Without these, people turn all notifications off and miss the important ones. |
| **Test notification button** | Lets anyone check their setup in seconds, with no guessing. |
| **Contact sync with clear consent**, a pause switch, a privacy page showing exactly what is shared, and delete buttons | Builds trust and keeps you on the right side of privacy rules. |
| **Caller ID card** from a local copy of the data that works offline | The shop knows who is calling, even with no signal. |
| **Offline mode and sync status** ("Live", "synced 5 min ago", "offline") | Staff always know whether they can trust what they see. |
| **"Sync now" button that shows its result** | A button with no visible answer feels broken. |
| **Settings screens:** text size, dark mode, languages (English, Sinhala, Tamil) | Staff of all ages and languages can use it comfortably. |
| **App update notice:** a minimum version and a latest version; "Update required" blocks very old apps | Lets you retire old, risky versions without a support call. |
| **Missed-call list, follow-up reminders, notes after a call** | Turns phone calls into follow-up work that does not get lost. |
| **Click-to-call from the desktop** | Staff save time dialling from the counter computer. |
| **Save unknown caller as a lead** | Every new caller becomes a possible customer. |
| **Offline customer search on the phone** | Look up a customer anywhere, even with no signal. |
| **The full web system inside the phone app**, with app lock, auto sign-out, a back button and PDF sharing | One place for everything, with basic safety for a device that can be lost. |

---

## 4. Unique features that set this app apart

These are what a generic app would not give you.

- **Call screen designer.** The shop designs what the incoming-call card shows: which fields, in what order, which colours and buttons. A live preview and "send a sample to my phone" let you check it right away. *Valuable because every shop has different needs and can adjust it without asking a developer.*
- **Caller brief.** Before you answer, the call screen shows the customer's rating, what they still owe and which machines they have out. *Valuable because you can decide how to handle the call before you say hello.*
- **"Open on desktop."** From the phone's call card, open that customer on the shop's counter computer. *Valuable because the phone call and the full record meet with one tap.*
- **Two-way data policy.** The shop decides what each phone receives and what it sends. Roles get "brief profiles"; for example, a counter phone never receives money amounts. There are lock-screen modes, a choice of which contacts to share, which calls to log, a business-SIM-only filter on dual-SIM phones, and retention periods. *Valuable because the owner stays in control of data, and staff see only what they need.*
- **Privacy rule for synced contacts.** Synced contacts appear only after 9 or more digits of a phone number are typed, and never by name. *Valuable because staff cannot browse the owner's personal contacts, which makes it safe to share them at all.*
- **Notifications in each person's language and privacy level, with editable templates.** *Valuable because a Sinhala-speaking cashier and an English-speaking owner each get a message that suits them, and sensitive amounts stay hidden from people who should not see them.*

---

## 5. Bugs we hit and how to avoid them

This is the core of the playbook. Each item says what happened, why, and the rule.

### 5.1 Permissions and crashes

**Bug 1. Turning on contact sharing closed the app.**
*What happened:* The moment someone switched on contact sharing, the app shut down.
*Why:* The app started watching contacts before Android had given permission.
*Rule:* Ask for permission first. Guard every protected action so it only runs when permission is granted. Always handle the answer "no" with a calm message, never a crash.

**Bug 2. A web page crashed with "React error 310".**
*What happened:* A page worked in testing but crashed in the live system.
*Why:* A new check was added after an early "still loading" exit. So the page ran different code on different visits, which the page's framework does not allow. The project's checker did not cover those files, so it was found only in production.
*Rule:* Put all checks at the top of a page, before any early exit. And make sure the automatic checker covers every file. Ask the AI: "Which files are not covered by our checks?"

### 5.2 Silent failures

A silent failure is when something breaks and nobody is told. These cost us the most time.

**Bug 3. Contacts never synced.**
*What happened:* Contact sync appeared to run, but nothing arrived on the server.
*Why:* The server accepted contact IDs up to 64 characters. Pixel phones produce longer ones. The server rejected the whole batch, and the app dropped it silently.
*Rule:* Share one set of limits between the app and the server, in one file. Record every error, and show it on screen.

**Bug 4. "Sync now" gave no feedback.**
*What happened:* People pressed the button and saw nothing.
*Why:* It queued a background job and returned at once.
*Rule:* Every button needs visible progress ("Syncing...") and a result ("Done, 120 contacts" or "Failed, reason").

**Bug 5. The server turned every unexpected error into "please pair again".**
*What happened:* A small server problem could make a phone wipe itself and ask to pair again. It also hid the real reason pairing failed, so finding the pairing loop (Bug 7) took much longer.
*Why:* One catch-all error message treated "the server had a problem" the same as "you have been removed".
*Rule:* Keep these two apart. "Server problem" means try again later and keep the data. "You are removed" is a deliberate, specific signal. Log why anything was refused.

### 5.3 Code written but never built

**Bug 6. The first cloud build found many errors.**
*What happened:* Several AI agents wrote the Android app without ever building it. The first build found a missing app icon, a wrong database table name and a wrong variable. Later releases also had build errors.
*Why:* Nobody compiled the code, so nobody knew it was broken.
*Rule:* Set up the cloud build on day one, and require a green build before a change is accepted.

### 5.4 Pairing and security

**Bug 7. The pairing loop.**
*What happened:* A phone paired, then bounced back to the pairing screen again and again.
*Why:* A phone keeps the same notification address when it pairs again. Unpairing the old pairing from the desktop sent a "you're removed" message to that address. It arrived late and wiped the *new* pairing.
*Rule:* Every message names the pairing it is for. The app ignores messages that do not match its current pairing. The server does not send to a phone that has paired again.

**Bug 8. A revoked phone kept customer data until someone opened the app.**
*Rule:* Wipe on the revoke signal itself. Do not wait for the next app open.

**Bug 9. A deactivated staff member's phone kept working.**
*Rule:* Check the user's status on every request, not only at sign-in.

**Bug 10. Any paired phone could download every customer's money details.**
*Why:* The check only asked "is this phone paired?" and not "is this person allowed to see this?"
*Rule:* Phone data needs a permission check and a per-role profile. Ask the AI: "What can the least-trusted role see?"

**Bug 11. Contact privacy could be bypassed.**
*What happened:* Someone could step through contact numbers one by one and read details for any of them.
*Why:* "Use contact" returned details for any contact ID.
*Rule:* Require the matching phone number with each request, and limit how many requests are allowed per minute.

**Bug 12. "Unpair" on the phone only cleared the phone.**
*Why:* The server kept sending to it.
*Rule:* Unpair must clear both sides, the phone and the server.

**Bug 13. A late subscription payment would have unpaired every phone.**
*Rule:* Keep "paused" separate from "removed". A paused shop keeps its pairings and gets a clear message.

**Bug 14. Unlimited pairing attempts.**
*Why:* Pairing codes could be guessed by trying many times.
*Rule:* Limit pairing attempts, and make codes short-lived.

**Bug 15. A new tab on a page could not be opened.**
*Why:* The page router kept a second list of allowed tabs, and nobody updated it.
*Rule:* Keep one list, in one place. If a list exists twice, expect them to drift apart.

### 5.5 Notifications

**Bug 16. Quiet hours did not work when the app was in the background.**
*Why:* In the background, Android shows some notifications itself, without running the app's code. So the app's quiet-hours check never ran.
*Rule:* The server sends data-only messages, and the app builds the notification itself. Then your rules always apply.

### 5.6 Calls

**Bug 17. One call created two records; a call's end was lost.**
*Why:* Android announces ringing twice. And if Android killed the app mid-call, the "call ended" message was lost.
*Rule:* Save call state to storage, not only to memory. Reuse a recent session instead of making a new one.

**Bug 18. Personal calls were logged.**
*Rule:* Add a business-SIM filter, and an option to log only customers and known contacts. Decide what not to log before deciding what to log.

### 5.7 Data

**Bug 19. "Overdue N days" went stale on the phone.**
*Why:* The server sent a number that was true only on the day it was sent.
*Rule:* Send the due date. Work out "days overdue" when the screen is shown.

**Bug 20. An empty contact scan could delete every synced contact on the server.**
*Rule:* Treat "suddenly empty" as an error, not as an instruction to delete. Ask for confirmation, or keep the old data.

### 5.8 Screens and language

**Bug 21. Hard-coded light colours broke dark mode.**
*Rule:* Never type a colour into a screen. Use named theme colours that change with the mode. Check both light and dark mode.

**Bug 22. A warning existed only in English.**
*What happened:* Sinhala users saw English text.
*Rule:* Every new text must exist in every language, and an automatic check must fail the build if one is missing.

### 5.9 Product decisions

**Bug 23. Customer search was changed to phone-only, then changed back.**
*What happened:* Two AI assistants understood the owner differently. One removed name search. It then had to be restored.
*Why:* "Search" meant two different kinds of data, and nobody wrote down which rule applied to which.
*Rule:* Write the rule in one sentence and confirm it with the owner before building. Treat different kinds of data separately. Our final rule:

> The shop's customers are found by name, ID card or phone. Synced phone contacts are found only by 9 or more digits of a phone number.

### 5.10 Working with several AI agents

**Bug 24. A second agent worked from older code and re-added features the owner had removed.**
*Rule:* One shared branch. Merge in order. After every merge, check that removed things stay removed. Keep a "removed on purpose" list in the spec.

**Bug 25. Several agents ran out of credits mid-release.**
*What happened:* Unsaved work was left scattered across four projects.
*Rule:* Commit often, on a named branch. Keep a written spec and a short "where we are" note, so the next agent can continue without guessing.

### 5.11 Deploying

**Bug 26. Deploy surprises.**
*What happened:* Each server restart caused about two minutes of errors, because of startup checks. A change to how notifications are sent meant older phone apps might stop showing them, so the new app had to be installed the same day. Database changes ran on every shop at once, so a fresh backup before each deploy was essential.
*Rule:* Use the careful deploy in Section 2, Step 7. In short: backup, add-only database changes, an on/off switch per shop, server then website then phone app, old apps keep working, a rollback plan, and restarts at quiet times.

**Bug 27. Real-phone behaviour could not be tested by the agents.**
*What happened:* Samsung and Vivo battery savers stopped background work. Lock-screen overlays and SIM labels behaved differently from phone to phone.
*Rule:* Keep a written real-device test list, and run it on at least two or three different brands before every release.

---

## 6. Pre-release checklist

Copy this into each release. Tick every line.

**Before building**
- [ ] The spec is written and the owner has read it.
- [ ] Open questions are answered (Section 7).
- [ ] Work is on one named branch.

**Checks**
- [ ] The automatic checks pass and cover every file.
- [ ] The cloud build of the phone app is green.
- [ ] Every new text exists in English, Sinhala and Tamil.
- [ ] Light mode and dark mode both look right.
- [ ] Removed features are still removed.

**Safety**
- [ ] Every button shows progress and a result.
- [ ] Errors are shown on screen and logged, never swallowed.
- [ ] "Server problem" and "you are removed" are different messages.
- [ ] Each role sees only what it should (try the least-trusted role).
- [ ] Pairing attempts and sensitive requests are rate-limited.
- [ ] A revoked, suspended or deactivated user loses access at once.
- [ ] Unpair clears both the phone and the server.

**Real phones**
- [ ] Tested on at least two or three brands (for example Pixel, Samsung, Vivo).
- [ ] Permission "no" answers do not crash anything.
- [ ] Notifications arrive with the app closed, and quiet hours are respected.
- [ ] Incoming call card, missed call and call end work on a real call.
- [ ] Battery saver on: background work still runs.
- [ ] Offline: the app opens, shows "offline", and recovers when signal returns.

**Deploy**
- [ ] Database backup taken and checked.
- [ ] Database changes only add, never delete.
- [ ] New features are behind a per-shop on/off switch, set to off.
- [ ] Order: server, then website, then phone app.
- [ ] Older phone apps still work; the minimum-version notice is set correctly.
- [ ] Rollback plan written, with named people.
- [ ] Restart planned for a quiet time.

**After release**
- [ ] Turned on for one friendly shop first.
- [ ] Watched errors for the first day.
- [ ] "Settings to make adjustable later" list updated.

---

## 7. Questions to settle with the owner before building

These are the decisions that caused rework. Get a written answer to each.

1. **Who can see what?** For each role, which data (especially money) may reach a phone? What does the least-trusted role see?
2. **How is each kind of data found?** For every kind of data (customers, contacts, items), how can it be searched: by name, by number, by ID? Write one sentence for each.
3. **What must never be shared?** Which personal data is off limits, even to the owner's staff? How is it hidden?
4. **What gets logged?** Which calls or events are recorded and which are ignored? How long are they kept?
5. **Who approves a new phone?** Is it automatic for the owner, and manual for staff? Who can suspend, revoke or wipe?
6. **What happens when a phone is lost, a person leaves, or a payment is late?** Define "paused" and "removed" separately.
7. **What does the owner want removed?** Write a "removed on purpose" list so it never comes back.
8. **Which languages are required on day one?** Every text must exist in all of them.
9. **What counts as "done"?** Which real phones, which brands, which tests?
10. **How is it released?** Which shop goes first? Who can turn the switch on? What is the rollback plan?
11. **What is the budget for AI work?** Plan for agents running out mid-release: how often to save, and who writes the "where we are" note.
12. **Which small settings can wait?** Add them to the "settings to make adjustable later" list instead of building them now.

---

*Last rule:* if two AI agents seem to disagree about what you meant, stop and write your answer in one sentence. Then confirm it before any more code is written.
