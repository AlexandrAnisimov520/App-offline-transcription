# BIMVO v3 - device acceptance checklist

**Status: NOT RUN in the authoring container.** Use a disposable emulator for destructive tests. Never clear application data on a phone containing important imported recordings.

## Build / start
- [ ] Open the project root in Android Studio, sync, run `:app:assembleDebug` and `:app:lintDebug`.
- [ ] Launch on Android 8+ and a recent Android version, portrait and landscape.
- [ ] Check launcher icon under circle and rounded-square masks; verify the mic is not cropped.
- [ ] Existing splash/login/reference styling and bottom navigation remain usable.

## Optional introduction
- [ ] Fresh local app data -> Demo access -> all three choices and visible Skip.
- [ ] Lecture, meeting and personal choices show distinct three-block information panels.
- [ ] Choose / finish, close and reopen, sign out/in -> no repeat popup.
- [ ] Skip, close and reopen -> no repeat popup. System Back also skips.
- [ ] Rotate on the selection panel and information panel -> same stage, no duplicate dialogs.
- [ ] Check smaller screen and large font sizes: content scrolls, Skip/Start remain reachable.

## Import and persistence
- [ ] Pick local MP3/WAV/M4A, then cancel the picker -> no phantom entry.
- [ ] Import -> actual bytes in app-private files/audio, metadata in the library, status Unprocessed.
- [ ] Import the same bytes under another name -> no duplicate recording or audio file.
- [ ] Import a different file with the same display name -> separate UUID-backed record.
- [ ] A file without readable duration remains stored and shows unknown duration.
- [ ] Empty file, revoked/read-failing provider and insufficient storage -> honest error, no partial library entry.
- [ ] Cancel a large import -> no .part residue. Check UI remains responsive.
- [ ] Rotate during import -> job continues, no duplicate job or leaked window.
- [ ] Navigate to an existing demo while import is copying -> completion does not replace the selected demo.
- [ ] Force-stop during import, reopen -> unfinished private copies cleaned, existing records intact.
- [ ] Complete import, force-stop/reopen -> recording remains. Rename persists.
- [ ] Delete the external original after successful import -> BIMVO copy is still present.
- [ ] Delete in BIMVO with confirmation -> only the application copy is removed, original survives.
- [ ] Rapid repeat clicks / delete while busy -> no duplicate jobs or corrupted selection.

## Library and hint switch
- [ ] Six sorting options work; unknown duration stays last; choice survives relaunch.
- [ ] Cyrillic case-insensitive name and existing-demo-text search; empty result state.
- [ ] Library statistics include real imports and explicitly label the three demo samples.
- [ ] Hints switch persists and controls optional help text, not essential demo/error notices.
- [ ] No language selector or additional unrequested profile settings.

## Speakers / timestamps / exports
- [ ] The three demos have distinct turns; importing audio never attaches any of them to it.
- [ ] Each turn shows speaker + start + end. Timeline uses the same intervals, including the 01:00 end tick.
- [ ] Edit text -> timeline snippet, search and export see the saved value after relaunch.
- [ ] Change speaker or bounds -> text card, map and export agree, IDs stay stable after time sorting.
- [ ] Try zero/reversed bounds using sliders -> start remains strictly before end.
- [ ] Player is explicitly a silent demo; tapping a turn moves its time to that turn.
- [ ] JSON file contains only transcript[] with speaker_id/string, start_ms/integer, end_ms/integer, text/string.
- [ ] TXT/SRT include speakers and both boundaries; escaped quotes, Cyrillic and emoji survive export.
- [ ] Create a new export, cancel the picker, rotate while picking, and simulate a write failure.
- [ ] No export success is reported for cancelled/failed writes.
- [ ] Unprocessed imported audio cannot export a fabricated transcript.
- [ ] Export layout has no added preview card.

## Offline and limits
- [ ] Airplane mode: local import, persistence, search, edits and export operate with an on-device provider.
- [ ] Microphone button honestly states recording is unavailable; no fake audio record is created.
- [ ] Simulated processing is entered only through the explicit demo action.
- [ ] No implication that ASR/diarization/LLM are present or that this is ready for model evaluation.
