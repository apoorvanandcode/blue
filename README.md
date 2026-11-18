# Blue

A personal expense tracker for Android that reads your bank SMS. Blue turns debit, credit and UPI messages into entries you confirm with a swipe, and shows where your money goes. Everything stays on your phone.

- **Daily automatic scan** at a time you choose, plus a manual "Scan now".
- **Review** each entry: swipe right to confirm, left to skip. Blue remembers the category per merchant.
- **Entries** and **Insights**: by category, account, person and merchant, compared with the previous period.
- **Upcoming**: mandates, auto-debits and deliveries, closed automatically when the matching SMS arrives.
- **Nicknames** for merchants (the bank's name is kept), and **own transfer** marking that keeps moves between your accounts out of totals.
- **Optional:** "Save where I pay" notes the phone's location when a payment SMS arrives (stored only on the phone).
- **Spam** sorting by sender, starred SMS.
- **Optional:** your own Google Gemini API key for SMS the built-in rules don't recognise.
- **Optional:** backup to your own Google Drive and export to Google Sheets.

Privacy policy: <https://apoorvanandcode.github.io/privacy.html>

## Install

Download the APK from [Releases](https://github.com/apoorvanandcode/blue/releases) and open it on your phone. Android will ask you to allow installs from your browser or Files app.

- **Android 16 or newer** is required right now.
- If Android blocks SMS access ("Restricted setting"), open App info → ⋮ → **Allow restricted settings**, then allow SMS. Blue's "It won't let me allow it" screen walks through this.

## Build it yourself

Requirements: JDK 17+, Android SDK 36.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # install on a connected phone
./gradlew :app:testDebugUnitTest    # unit tests, including ~100 SMS fixtures
```

### Release signing

Create a keystore, then a `keystore.properties` file in the project root (it's git-ignored):

```properties
storeFile=release/blue-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew :app:assembleRelease`. Without the file, release builds are unsigned.

### Google Drive and Sheets (only if you change the app or its key)

Google matches Android apps by **package name + signing certificate SHA-1**. If you build with your own key or change `applicationId`, sign-in will fail until you set up your own Google Cloud project:

1. Create a project and enable the **Google Drive API** and **Google Sheets API**.
2. Google Auth Platform → Clients → create an **Android** OAuth client with your package name and SHA-1 (`./gradlew signingReport` shows it).
3. Only the `drive.file` scope is used, so no sensitive-scope review is needed.

No client ID or secret is stored in the code.

### Gemini

Each user adds their own key in Settings (from [Google AI Studio](https://aistudio.google.com)). It is stored with Android Keystore and never included in backups.

## How it works

- `domain/parse`: rule-based classifier per bank, tested against SMS fixtures in `app/src/test/resources/fixtures` (anonymised).
- `domain/ai`: Gemini fallback through the Interactions API (`store: false`).
- `work/DailyScanWorker`: the scheduled daily scan (WorkManager).
- `data/db`: Room database; nothing leaves the phone except what you send to Gemini or your Drive.

Design and behaviour decisions are recorded in [DECISIONS.md](DECISIONS.md).

## Licence

MIT, see [LICENSE](LICENSE). Third-party fonts and icons are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
