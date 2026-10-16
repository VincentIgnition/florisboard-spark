# FlorisBoard Spark

A personal fork of [FlorisBoard](https://github.com/florisboard/florisboard) that adds word suggestions and autocorrect. For everything about FlorisBoard itself (features, permissions, contributing, translations), see the [original README](https://github.com/florisboard/florisboard#readme).

Based on upstream commit [`fe1241f`](https://github.com/florisboard/florisboard/commit/fe1241f4921b3eae923571ff7a3e113a7e10d677). `main` (the default branch) and `personal-suggestions` both hold the fork.

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
- New words are learned after you type them twice, along with their capitalization. A word is only protected from autocorrect after you type it three times or undo a correction of it, so a typo that slipped through once is still corrected later
- Words are learned even when typing fast, where the keyboard never sees the moment right after the space
- Next-word predictions blend your own typing history with how often words follow each other in English
- Nothing is learned in incognito mode, in fields that request no personalized learning, or in single-line fields like browser address bars that ask for no suggestions. Multi-line fields such as comment boxes still learn
- Learned data stays on the device in the app's private storage

### Autocorrect
A word ending in a space or punctuation is corrected on the spot, using the same decision the suggestion bar previews.

- Neighboring-key slips: `ks`, `kf`, `jt`, `nkt` and `clkses` become `is`, `if`, `it`, `not` and `closes`. One slip beats two even toward a more common word. Words of 8 or more letters get a wider search (`bapoenij` becomes `happening`)
- Where your finger landed counts. A tap on the edge of a key toward its neighbor makes the neighbor cheap to choose, a tap in the middle makes it expensive. `fot` becomes `got` when the f was tapped toward g, and `for` when the t was tapped toward r
- `i` and its contractions get their capital, missing apostrophes are restored (`dont`, `im`, `theyre`)
- The previous word decides between candidates: `it wad` becomes `it was`, but `the wad` stays because English never writes `the was`. Real words that fit where they were typed are left alone (`I sat`, `day to day`)
- Missing spaces are split (`thecat` becomes `the cat`, `hellobworld` becomes `hello world`), unless the previous word shows a single slip was meant (`it needa` becomes `it needs`, `I needa` becomes `I need a`)
- Accidental spaces are undone: an extra tap on the space bar (`abo ut` becomes `about`) or the space bar hit instead of `c`, `v`, `b`, `n` or `m` (`o e` becomes `one`)
- Once the next word is typed, the word before is checked again: `its a` becomes `it's a`, `there going` becomes `they're going`, `their is` becomes `there is`, `there house` becomes `their house`, `your welcome` becomes `you're welcome`, and `I want to fo home` ends as `go home`. A lone `k` before `am` or `think` becomes `I`
- Backspace right after a correction restores what you typed, and ending that word again keeps it
- Leaves alone learned words, slang like `lol`, `ur` and `u`, names mid-sentence, mixed-case words like `iPhone`, and about 42,000 real English words the bundled dictionary lacks (`wagged`)
- Disabled in password, email and URL fields. In browser address bars only a space triggers a correction, so a domain like `ycombinator.com` is never rewritten at the dot
- Can be turned off in Settings > Typing > Auto-correct

### Where suggestions appear
Apps can mark a text field as wanting no suggestions. Browsers set that mark on their address bar and on every web field with `autocomplete="off"`, an HTML attribute meant for browser autofill that sites put on search, chat and comment boxes. Like SwiftKey, this fork shows suggestions in those fields anyway, including the Brave and Chrome address bars. Suggestions are only withheld in password, number, phone and raw input fields.

### Dictionary cleanup
The bundled English dictionary was built from web text and contains common misspellings (`recieve`, `definately`, `teh`), apostrophe-less contractions (`dont`, `im`) and glued phrases (`ofthe`, `thankyou`, `noone`). These are filtered out at load time so the correct forms win. Real words that look like contractions (`well`, `were`, `its`, `ill`) or glued phrases (`todo`, `whatnot`) are kept. A few common words the dictionary lacks (`subreddit`, `upvote`, `modding`, `codebase`, `frontend`) are added so they are not split or corrected.

### Keyboard
- US English QWERTY layout instead of the German QWERTZ layout bundled upstream, with English long-press defaults (`d` gives `$`, vowels give acute accents first, `a` gives `@`)
- Optional arrow key row (left, up, down, right) under the letters, toggled in Settings > Keyboard > Arrow key row
- The Fn key (formerly a globe icon) opens the emoji panel. Long-press still opens the system keyboard picker
- Switching to the next or previous language works, where upstream left it unimplemented

### GIF search
The emoji panel has a GIF button that searches GIPHY, with trending GIFs shown before you type. In apps that accept GIFs, such as messaging apps, the GIF itself is sent. Elsewhere its link is inserted.

- Needs a free GIPHY API key: create an app at [developers.giphy.com](https://developers.giphy.com) (choose API, not SDK) and paste the key in Settings > Media > GIPHY API key. The key is stored only on the device
- Free keys allow 100 requests per hour, so searches wait until you pause typing and trending is fetched once per panel opening
- Tenor is not supported: Google shut down the Tenor API for third-party apps on June 30, 2026
- This is the only feature that uses the network, which required adding the `INTERNET` permission upstream FlorisBoard does not have. On GrapheneOS and similar systems the Network permission may be off after updating; such systems make it look like the network is down, so when the panel sees no network it says the permission is probably off and links to the app info screen

### Emoji search
The emoji panel has a Search button next to GIF. It searches the official emoji names and keywords: every word you type has to match, exact matches rank first, recently used emojis get a small boost, and only emojis the phone can draw are shown. Recent and pinned emojis show before you type. Typing `:` in the text field for emoji suggestions uses the same matching.

### Spell checker
The FlorisBoard spell checker service uses the same engine. Once it is selected as the system spell checker (Settings > Typing in the app links to it), misspellings get underlined with corrections in apps that support it.

### Diagnostics
Open FlorisBoard+ and go to Other > Devtools, then turn on Enable developer tools.

- Show input state overlay shows the app package, the raw input type, and whether suggestions are shown or why they are blocked
- Debug log includes why the keyboard's process last stopped (crash, freeze, out of memory, update), saved crash reports, and the keyboard's own errors and warnings, such as why autocorrect skipped a word. The typed text itself is never logged

### Editor tracking and speed
Upstream follows the cursor only through cursor anchor updates, which Chromium stops sending when a page re-renders. On sites like Reddit the keyboard's idea of the cursor then went stale and typing stopped reaching the field. This fork also listens to the standard selection updates, which take over once an editor sends them.

Following AOSP LatinIME: cursor and text are read from the editor in one call, intermediate selection updates on the way to the expected cursor position are skipped instead of asking the editor again, and suggestions are worked out 100 ms after you pause typing instead of on every key. Spell check requests, which browsers send in bulk, never hold up typing.

### Word data
`app/src/main/assets/ime/dict/bigrams.tsv` (word pair statistics), `unverified.txt` and `valid.txt` are generated by `utils/gen_dict_assets.py` from the [SymSpell](https://github.com/wolfgarbe/SymSpell) English frequency and bigram dictionaries (MIT), which are derived from [Google Books Ngram](https://books.google.com/ngrams) data (CC BY 3.0) and the [SCOWL](http://wordlist.aspell.net/) word list.

## Tested
Used daily on a Pixel 7 Pro running Android 17, where every feature described above is confirmed working.

## Limitations
- English only
- No swipe typing (disabled upstream pending a rewrite)
- The word pair data comes from books, so phrases common in chat but rare in books get less credit until the keyboard learns your habits
- Autocorrect does not use what comes after the cursor, and looks back only one word

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
./gradlew :app:testDebugUnitTest --tests "dev.patrickgold.florisboard.ime.editor.*"
./gradlew :app:testDebugUnitTest --tests "dev.patrickgold.florisboard.ime.media.gif.*"
./gradlew :app:testDebugUnitTest --tests "dev.patrickgold.florisboard.ime.media.emoji.*"
```

The first covers the suggestion and autocorrect engine against the real dictionary and word pair data. The second compiles the bundled keyboard layouts the same way the app does at startup, which catches layout errors that would otherwise leave the keyboard blank. The third covers which fields get suggestions, using the input flags browsers actually send. The fourth covers parsing GIPHY responses and the network error messages. The fifth covers emoji search against the bundled emoji data.

## License
Apache License 2.0, same as upstream. See [LICENSE](LICENSE).
