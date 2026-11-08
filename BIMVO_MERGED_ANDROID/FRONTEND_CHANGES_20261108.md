# Frontend changes — 8 November 2026

Based on `codex/parakeet-600m` (`a97d0c8`). Recording now shows a microphone-reactive circular animation and a timer/stop strip on other screens. Personal recordings and demo examples have separate library entry points; the local profile, empty states and about/onboarding text are updated. Existing playback, timeline seeking, sorting, automatic transcription and deletion remain in place.

Audio can be saved under its Russian display name. The source-file rename action uses the Android document picker and verifies the selected file SHA-256 before calling the provider. Read-only/unsupported providers show an error; no raw filesystem path or extra storage permission is used. The private canonical audio copy stays unchanged.

Validation: the staged frontend-only Java source tree compiles against the installed Android SDK/API dependencies. The local integrated build passed 69 core checks, SQLite migration checks and source-identity/provider contract tests, and produced a signed debug APK. That APK also contains separate uncommitted diarization integration and therefore is not an exact artifact of this frontend-only commit. The new UI has not been manually validated on a physical Android; emulator installation was not completed before delivery.

Excluded: diarization integration/research, DER/WER experiments, model weights, APKs, generated build/cache files and temporary reports. This branch retains the ASR/diarization implementation of its base; existing base model preparation requirements still apply. No merge or PR is performed.
