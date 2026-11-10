<div align="center">

# TAZ

**A private, offline vault for your notes, passwords and files.**

No account. No cloud. No tracking. Everything stays on your phone.

[![Latest release](https://img.shields.io/github/v/release/zero7goat/TAZ?style=for-the-badge&label=download&color=2ea44f)](https://github.com/zero7goat/TAZ/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#built-with)
[![License](https://img.shields.io/github/license/zero7goat/TAZ?style=for-the-badge)](LICENSE)

### [⬇️ Download the latest APK](https://github.com/zero7goat/TAZ/releases/latest)

</div>

---

## Download & install

1. Open the **[Releases page](https://github.com/zero7goat/TAZ/releases/latest)**.
2. Under **Assets**, download **`TAZ-<version>.apk`**.
3. Open the file on your Android phone. If asked, allow *"Install unknown apps"* for your browser or file manager.
4. Launch **TAZ**, set a passcode, and you're done.

> TAZ is not on the Play Store. You can verify the download with the SHA-256 checksum published in each release.

## Features

### 📝 Notes
- Rich notes with **Markdown-style formatting**, a formatting toolbar and a read-only preview mode
- Insert **images** into notes (JPEG, PNG, WebP, GIF, up to 25 MB each)
- **Colour-code** notes with 12 soft colours that stay readable in light, dark and AMOLED themes; the colour tints the note in the list and in the editor
- **Pin** the important ones and mark **favourites**
- Organise with **nested folders**
- **Search inside note text**: every word you type must appear somewhere in the title, description or text (any order). Results show a snippet around the match with your words in bold
- Deleting a folder never deletes what's inside it; items move to the top level

### 🔑 Passwords
- Store logins with service name, website, username, password and notes
- Username, password and notes are **encrypted with AES-256-GCM**, with the key held in the **Android Keystore** and never leaving the device
- Show or hide a password with the **eye icon** inside the field
- Unsaved login forms survive switching apps and auto-lock (kept encrypted, never as plain text)
- Built-in **password generator** (8–64 characters, choose upper/lower case, digits and symbols, powered by `SecureRandom`)
- Search by website, group into folders, mark favourites
- **Password health check**: finds weak, reused and year-old passwords, entirely on your phone. Nothing is sent anywhere and no password is ever shown in the report
- Copied passwords are flagged as sensitive and **cleared from the clipboard automatically** (10 s to 2 min, your choice)

### 📁 Files
- Import any file into TAZ's private storage
- Rename files, add **descriptions and keywords**, favourite them and sort them into folders
- Automatic categories: images, videos, audio, documents, archives
- **Built-in preview** for images, PDFs and text/code files; other files open in your preferred app
- Share or open files without leaving the vault workflow

### 🔒 Security & privacy
- **App lock** with a 4–16 digit passcode, hashed with **PBKDF2-HMAC-SHA256** (200,000 iterations, per-passcode salt)
- **Biometric unlock** (fingerprint / face) as an optional convenience
- **Auto-lock**: immediately, 30 s, 1 min, 5 min, 15 min, or only on restart
- **Brute-force protection**: increasing lockout delays after repeated wrong passcodes
- **Screenshot blocking** and a hidden preview in the recent-apps switcher
- **No internet permission at all**: the app physically cannot send your data anywhere
- Forgotten your passcode? An explicit *erase everything* reset lets you start fresh (there is no backdoor)

### 🗂️ Folders & selection
- Create folders in one tap, including **nested folders**
- **Long-press to select** several notes, logins or files, then **move** them with a folder picker that always shows the destination, or send them to the Trash
- Deleting a folder never deletes what is inside: its contents move up one level

### 🗑️ Trash
- Deleted notes, logins and files go to the **Trash** (Settings → Trash) and can be restored for **30 days**
- Items are removed for good after 30 days, or earlier with *Delete* / *Empty trash*
- Trashed items are not included in backups or spreadsheet exports

### ⚡ Quick note
- **Long-press the TAZ icon** on your home screen and choose **New note**. TAZ still asks for your passcode first

### 💾 Backup, restore & export
- **Encrypted backup** to a single `.tazbackup` file, protected by a passphrase you choose
  (AES-256-GCM, key from PBKDF2-HMAC-SHA256, chunked & authenticated so tampering or truncation is detected)
- Backups are **portable**: restore after a reinstall or on a new phone
- Restore safely: a damaged, incomplete or wrong-passphrase backup never changes your current data
- **Export to spreadsheet (`.xlsx`)**: a readable index of your notes, files and login names/sites.
  Passwords, usernames and file contents are deliberately **never** included

### 🎨 Personalisation
- Themes: **System**, **Light**, **Dark** and **AMOLED** true black
- Four text sizes: small, default, large, extra large
- Bundled fonts (Poppins, Liberation Serif, DejaVu Sans Mono) that work fully offline
- Material 3 design built entirely with Jetpack Compose

## Requirements

- Android **8.0 (API 26)** or newer

## Built with

| | |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Storage | Room (SQLite), DataStore |
| Security | Android Keystore, AES-256-GCM, PBKDF2, BiometricPrompt |
| Export | [fastexcel](https://github.com/dhatim/fastexcel) |
| Build | Gradle (Kotlin DSL), KSP |

## Build from source

```bash
git clone https://github.com/zero7goat/TAZ.git
cd TAZ
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # run unit tests
```

The APK is written to `app/build/outputs/apk/`.
You need JDK 17+ (the one bundled with Android Studio works) and the Android SDK (API 37).

### Signed release build

Create a `keystore.properties` file in the project root (it is git-ignored):

```properties
storeFile=C:/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run `./gradlew assembleRelease`. Without this file the release APK is built unsigned.

## Project structure

```
app/src/main/java/com/zero7goat/taz/
├── core/        # backup, crypto, database, security, settings, storage, navigation, shared UI
└── features/    # notes, passwords, files, settings (data / domain / presentation)
```

The crypto, backup format, lock policy and password generator are pure-JVM code with unit tests in `app/src/test`.

## Important notes

- **There is no recovery for a forgotten passcode or backup passphrase.** That is the price of having no cloud and no account. Keep your passphrase safe.
- Files you store in the vault are kept in the app's private storage (protected by Android's app sandbox) but are not individually encrypted. Use encrypted backups when moving data.
- Back up regularly. Uninstalling the app or clearing its data deletes the vault.

## License

Released under the [MIT License](LICENSE). Bundled fonts keep their own licenses (SIL Open Font License); see `app/src/main/assets/licenses/fonts.txt`.

---

<div align="center">Made by <a href="https://github.com/zero7goat">zero7goat</a></div>
