# FlorisBoard Spark

A personal fork of [FlorisBoard](https://github.com/florisboard/florisboard) that adds word suggestions and autocorrect. For everything about FlorisBoard itself (features, permissions, contributing, translations), see the [original README](https://github.com/florisboard/florisboard#readme).

Based on upstream commit [`fe1241f`](https://github.com/florisboard/florisboard/commit/fe1241f4921b3eae923571ff7a3e113a7e10d677). All fork changes live on the `personal-suggestions` branch; `main` tracks upstream.

## What this fork adds

### Word suggestions
Upstream FlorisBoard currently ships without suggestions. This fork implements them in the Latin language provider (`ime/nlp/latin/`).

- Completions for the word you are typing, ranked by word frequency and by what you usually type after the previous word
- Typo correction that knows which keys sit next to each other on a QWERTY keyboard, so `teh`, `jsut`, `wnat` and `probly` resolve to `the`, `just`, `want` and `probably`
- Missing apostrophes are fixed cheaply: `dont` becomes `don't`, `im` becomes `I'm`
- Capitalization follows what you type (`Hel` gives `Hello`, `HEL` gives `HELLO`)
- Tapping a suggestion replaces the current word and adds a space
- Long-press a suggestion to remove it until you type that word again

### Learning
- New words are learned after you type them twice, along with their capitalization
- Next-word predictions come from your own typing history
- Nothing is learned in incognito mode or in fields that request no personalized learning
- Learned data stays on the device in the app's private storage

### Autocorrect
- An unknown word ending in a space or punctuation is replaced by its best correction, which is shown first in the suggestion bar beforehand
- Backspace right after a correction restores the original word and teaches it, so it is not corrected again
- Never touches dictionary words, learned words, slang like `lol` or `ur`, mixed-case words like `iPhone`, or gibberish
- Disabled in password, email and URL fields
- Can be turned off in Settings > Typing > Auto-correct

### Dictionary cleanup
The bundled English dictionary was built from web text and contains common misspellings (`recieve`, `definately`, `teh`) and apostrophe-less contractions (`dont`, `im`). These are filtered out at load time so the correct forms win. Real words that look like contractions (`well`, `were`, `its`, `ill`) are kept.

### Keyboard
- US English QWERTY layout instead of the German QWERTZ layout bundled upstream, with English long-press defaults (`d` gives `$`, vowels give acute accents first, `a` gives `@`)
- Optional arrow key row (left, up, down, right) under the letters, toggled in Settings > Keyboard > Arrow key row
- The Fn key (formerly a globe icon) opens the emoji panel. Long-press still opens the system keyboard picker
- Switching to the next or previous language works, where upstream left it unimplemented

### Spell checker
The FlorisBoard spell checker service uses the same engine. Once it is selected as the system spell checker (Settings > Typing in the app links to it), misspellings get underlined with corrections in apps that support it.

## Limitations
- English only
- No swipe typing (disabled upstream pending a rewrite)
- Suggestions are computed slightly behind your typing. If you press space before the suggestion bar updates, that word is left uncorrected rather than corrected based on stale input

## Building

### Requirements
- JDK 17 or newer
- Android SDK with platform 37 and build tools 36.0.0
- Android NDK 29.0.14206865 and CMake 4.1.2
- Rust with the `aarch64-linux-android` target (`rustup target add aarch64-linux-android`)

The native library is built for `arm64-v8a` only (`lib/native/build.gradle.kts`), which covers essentially all current Android phones.

### Signing
The `personal` build type installs alongside official FlorisBoard as **FlorisBoard+** (`dev.patrickgold.florisboard.personal`). It is signed with a key that is not in this repository. Place it at `keystore/personal.jks` and create `keystore.properties` in the repository root:

```
storeFile=keystore/personal.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=personal
keyPassword=YOUR_KEY_PASSWORD
```

Both files are git-ignored. Updates only install over an existing copy when signed with the same key.

### Build
```
./gradlew :app:assemblePersonal
```

The APK is written to `app/build/outputs/apk/personal/app-personal.apk`.

### Tests
```
./gradlew :app:testDebugUnitTest --tests "dev.patrickgold.florisboard.ime.nlp.latin.*"
./gradlew :app:testDebugUnitTest --tests "dev.patrickgold.florisboard.ime.keyboard3.extension.*"
```

The first covers the suggestion and autocorrect engine against the real dictionary. The second compiles the bundled keyboard layouts the same way the app does at startup, which catches layout errors that would otherwise leave the keyboard blank.

## License
Apache License 2.0, same as upstream. See [LICENSE](LICENSE).
