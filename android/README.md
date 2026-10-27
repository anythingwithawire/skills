# Android apps

Two apps sharing one `core` module (on-device speech listener, the basis-for-listening gate, and the Claude API plumbing):

- **Commentator** (`commentator/`): live commentary cards on a conversation.
- **Bullshit Detector** (`bsdetector/`): flashes the screen red with BULLSHIT when Claude scores what it just heard above your sensitivity threshold.

Both use only on-device speech recognition and need the same basis for listening each session, so everything under **Privacy and the law** below applies to both.

## Bullshit Detector

Each utterance (speech up to a pause) goes to Claude on its own as soon as you stop talking, with no earlier context, and Claude scores it from 0 to 100. Anything heard while a request is running is sent straight after it. Nothing is kept: no transcript, no log, no share.

The **sensitivity** slider sets the score that triggers the flash: sensitivity 0 flashes only at 90 or more (blatant fabrication), 50 at 50, and 100 at 10 (the faintest whiff). The status line shows the last score so you can calibrate the slider. Because the detector sees one utterance at a time, it judges claims against general knowledge and cannot catch someone contradicting what they said five minutes ago.

## Commentator

Listens to a conversation through the phone microphone, transcribes it with Android's on-device speech recogniser, and every few seconds sends the new part of the transcript to Claude. Claude's commentary appears on screen as cards, newest first:

- **❓ Question**: something that should be asked or pinned down
- **⚠️ Doubt**: a claim that looks shaky, unsupported, or inconsistent with what was said earlier
- **➕ Info**: extra information that would help
- **✏️ Correction**: a statement that is wrong, with the correct position

Type a context before starting (for example "Family law property settlement discussion, WA" or "SIL verification meeting, IEC 61511") and the commentary is framed around it.

## Getting the APKs

Every push that touches `android/` runs the **Android apps** workflow. Open the run in the repository's Actions tab and download the `commentator-debug-apk` or `bsdetector-debug-apk` artifact (each a zip holding the APK). Copy it to the phone, allow installs from that source, and install.

Each CI run signs with a fresh debug key, so installing a newer build over an older one can fail with a signature mismatch: uninstall the old one first.

## First run

1. Open the menu, choose **Settings**, and paste a Claude API key from the [Claude Console](https://platform.claude.com/). Usage is billed to that key.
2. Grant microphone access when asked.
3. Choose the basis for listening, type a context, and tap **Start listening**.

Settings also hold the model (default `claude-opus-5-5`), the commentary interval (default 25 seconds, minimum 10), the default jurisdiction (default Western Australia), the recognition language (default `en-AU`), and ephemeral mode (on by default).

## Basis for listening

Every session starts with a choice of basis, which clears when the session stops and is stamped at the top of the session log:

- **In court, with the court's leave.** Claude is told to help the way a McKenzie friend does: questions to put, points to note, errors to raise, and never interrupting the court.
- **Every principal party has consented.** Surveillance Devices Act 1998 (WA) s 5(3)(c) and s 9(2)(a)(ii).
- **Reasonably necessary to protect my lawful interests.** s 5(3)(d) and s 9(2)(a)(vi). A confirmation dialog explains that this exception is narrow and that the burden of justifying it is yours.

## Ephemeral mode

On by default. Each piece of transcript is held only until the next send (at most one commentary interval), then discarded. Claude gets the new speech plus its own earlier comments, which act as its notes of the conversation, and never sees earlier speech again. The session log, and so **Share session**, holds comments only. If a request fails in this mode, that speech is gone and the error card says so.

With ephemeral mode off, the app keeps the whole transcript as one growing conversation with Claude (cheaper per request through prompt caching, and Claude sees everything), and the session log includes the transcript.

Either way, each piece of speech that Claude receives is a verbatim transcript. Tokenising does not change that: tokens decode back to the exact text.

## Privacy and the law

Under the Surveillance Devices Act 1998 (WA) s 5(1), using a listening device to record, monitor or listen to a private conversation you are not a party to, or to record one you are a party to, is an offence unless an exception applies. The cleanest exception is s 5(3)(c): every principal party consents. Sending the transcript to the API is arguably also communicating a record of the conversation under s 9(1), which has its own exceptions in s 9(2). The app will not start until you choose a basis (see above). No authority has been found on whether live speech-to-text with no saved audio is a "record" under the Act, so treat that question as open.

What leaves the phone:

- **Audio**: never. The app uses only Android's on-device recogniser (Android 13 or later, with the recognition language pack installed). If the phone cannot recognise on-device, the app refuses to listen instead of falling back to a cloud recogniser.
- **Transcript text** goes to the Anthropic API for commentary, verbatim, a chunk at a time.

Nothing is written to storage. The session log lives in memory until you clear the session or close the app; **Share session** hands it to another app only when you ask.

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

The APKs land in `commentator/build/outputs/apk/debug/` and `bsdetector/build/outputs/apk/debug/`.
