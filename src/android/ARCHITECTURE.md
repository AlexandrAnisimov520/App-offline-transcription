# BIMVO v3 architecture and integration boundary

## Runtime flow

System `ACTION_OPEN_DOCUMENT` picker -> `BimvoApplication` single IO executor -> `AudioImporter` -> `ImportFiles` streaming copy/SHA-256 -> app-private `files/audio/<uuid>.<extension>` -> `LibraryStore` SQLite metadata -> immutable `Recording`/`Utterance` snapshots -> native Java Views.

`BimvoApplication` owns jobs and transient operation state, not the Activity. Observers attach in `onStart` and detach in `onStop`. Copy and database operations do not run on the UI thread. Rotation keeps the process-scoped job; process death does NOT resume an incomplete import. Startup recovery removes partial/orphan files from the dedicated private audio directory and restores staged deletions when the row still exists.

`Utterance` is the single source for speaker IDs and millisecond intervals. `TimelineView`, text cards, search and `TranscriptText` consume the same snapshot. Each recording has its own rows; demo edits cannot modify another recording. The three demos are seeded only when creating the database.

The file picker does not grant broad storage access. The app copies only the explicitly selected file, temporarily persists the URI grant when supported, then releases it. Import hashes prevent duplicate bytes being stored under different names. A duration that cannot be extracted is represented by -1, not an invented value.

## File export

A current transcript snapshot is serialized into a private temporary export file. `ACTION_CREATE_DOCUMENT` selects the explicit destination. That staged snapshot is copied on the IO executor; success is posted only after write/flush/close. The staged file token, not transcript contents, is stored in Activity state. Cancelling deletes the stage. Old export stages expire during startup cleanup.

JSON output uses the exact public contract: `{ "transcript": [{ "speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "..." }] }`.

## Future model integration

No model runtime, decoder pipeline, VAD, ASR, diarization or LLM is bundled. Imported records currently have an empty utterance list. A future local inference implementation must decode the stored source and write actual utterance rows for that recording, then refresh the snapshot. It must never call the demonstration data path or attach a demo transcript to imported media.

Current UI descriptions/statuses and the silent demo-player flow will also need to be extended when real inference and playback are added. This is not a claim that model integration is already complete or plug-and-play.

## Existing build system

Java 17 source; no third-party app runtime dependencies added. Existing AGP/Gradle/SDK targets preserved. The small Gradle bootstrap shipped in v2 is retained; it is not a newly supplied official Gradle Wrapper binary. Its Windows launcher now propagates the process exit status correctly.

## Official Android API references consulted

- Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- App-specific storage: https://developer.android.com/training/data-storage/app-specific
- Adaptive icons: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive

These references describe platform APIs, not evidence that this project was built or run on Android in the authoring environment.
