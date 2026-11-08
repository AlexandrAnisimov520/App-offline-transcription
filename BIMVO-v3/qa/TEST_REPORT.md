# BIMVO v3 - verification report

## Executed successfully

- **69 Java core assertions**: actual production `Utterance`, `Recording`, `TranscriptText`, `ImportFiles` classes compiled with `javac --release 17` and executed. Includes byte-for-byte copy, SHA-256, short reads, 64 KiB buffer bound, empty files, read failures, initial/mid-copy cancellation, low-disk-space rejection and partial cleanup, path/name sanitation, search, immutable snapshots, stable selection IDs, timing edits, JSON/TXT/SRT serialization.
- **Exact JSON contract and Unicode round trip**: generated JSON parsed by Python; exact field names, integer millisecond timestamps, escaped quotes/control characters, Cyrillic and emoji verified.
- **7 host SQLite checks**: the CREATE statements extracted from production Java executed in SQLite. Interval constraints, foreign keys, content-hash uniqueness, chronology, isolated edits and cascade deletion verified. This is host SQLite, not Android instrumentation.
- **110 structural checks**: Java/resource references, XML parsing, manifest registration and permissions, assets, lifecycle hooks, intent wiring, persistence paths, build configuration, approved reference files and user-artwork SHA-256.
- **13 Java source files parsed** by the JDK compiler parser. Parsing checks syntax, NOT Android API linkage or resource compilation.
- Launcher-art viewport checked under circular and rounded-square adaptive masks. This is an asset preview, NOT an Android screenshot.

## Not executed / blocked

- `:app:assembleDebug`: **did not complete**. The existing Gradle bootstrap could not resolve `services.gradle.org` (`UnknownHostException`). Android SDK/build tools are also absent from this authoring environment.
- No APK generated. No claim of full Android compilation or successful installation.
- Android Lint, actual Android SQLite/SAF execution, activity lifecycle instrumentation, TalkBack and visual layout checks on devices/emulators were not run.
- `MANUAL_TESTS.md` lists the required device follow-up; its entries are intentionally unchecked.

## Evidence

- `results/core_tests.txt`
- `results/structure_tests.txt`
- `results/build_attempt.txt`

Run the portable checks with `python qa/verify_structure.py` and `python qa/run_core_tests.py`.
Run the Android build in an Android Studio/SDK environment: `gradlew.bat :app:assembleDebug`.

## Functional boundary

Real import/persistence/search/rename/delete/export code is implemented. Actual ASR, diarization, LLM, microphone recording and audible playback are NOT implemented. Demo transcripts are labelled and isolated from imported audio. This version does not yet satisfy the full hackathon inference requirements.
