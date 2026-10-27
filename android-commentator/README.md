# Commentator (Android)

Listens to a conversation through the phone microphone, transcribes it with Android's built-in speech recogniser, and every few seconds sends the new part of the transcript to Claude. Claude's commentary appears on screen as cards, newest first:

- **❓ Question**: something that should be asked or pinned down
- **⚠️ Doubt**: a claim that looks shaky, unsupported, or inconsistent with what was said earlier
- **➕ Info**: extra information that would help
- **✏️ Correction**: a statement that is wrong, with the correct position

Type a context before starting (for example "Family law property settlement discussion, WA" or "SIL verification meeting, IEC 61511") and the commentary is framed around it.

## Getting the APK

Every push that touches `android-commentator/` runs the **Android Commentator APK** workflow. Open the run in the repository's Actions tab and download the `commentator-debug-apk` artifact (a zip holding `app-debug.apk`). Copy it to the phone, allow installs from that source, and install.

Each CI run signs with a fresh debug key, so installing a newer build over an older one can fail with a signature mismatch: uninstall the old one first.

## First run

1. Open the menu, choose **Settings**, and paste a Claude API key from the [Claude Console](https://platform.claude.com/). Usage is billed to that key.
2. Grant microphone access when asked.
3. Tick the consent box, type a context, and tap **Start listening**.

Settings also hold the model (default `claude-opus-5-5`), the commentary interval (default 25 seconds, minimum 10), the default jurisdiction (default Western Australia), and whether to prefer on-device recognition.

## Privacy and the law

Under the Surveillance Devices Act 1998 (WA) s 5(1), using a listening device to record, monitor or listen to a private conversation you are not a party to, or to record one you are a party to, is an offence unless an exception applies. The cleanest exception is s 5(3)(c): every principal party consents. The app will not start until you tick the consent box, and the box clears after every session.

What leaves the phone:

- **Audio**: with on-device recognition (Android 13+ with the en-AU pack installed), audio stays on the phone. Otherwise the system recognition service may send audio to its provider (usually Google).
- **Transcript text** goes to the Anthropic API for commentary.

Nothing is written to storage. The transcript and comments live in memory until you clear the session or close the app; **Share session** hands them to another app only when you ask.

## Known limits

- Android's recogniser stops after each pause and the app restarts it, so some phones beep on every restart and words at the boundary can be lost.
- There are no speaker labels, and recognition errors are passed to Claude as is (the prompt tells it to allow for them).
- The app keeps the screen on while listening. It does not listen in the background or with the screen off.
- The API key is stored in the app's private preferences, protected by Android's app sandbox but not separately encrypted.

## Building locally

Needs JDK 17+ and the Android SDK (platform 35). Put `sdk.dir=/path/to/android-sdk` in `local.properties`, then:

```
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
