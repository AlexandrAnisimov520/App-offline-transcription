# BIMVO v3 design constraints

- Preserve the approved near-black graphite UI, typography hierarchy, spacing and minimal line icons. Existing splash/login artwork is unchanged.
- New launcher assets come from the user's supplied cyan crystal-microphone artwork. Only outer padding, sizing and launcher masks are adapted; no regenerated artwork.
- Optional onboarding uses the existing palette and native lightweight views: scrollable three-choice dialog, distinct three-benefit information panels, fixed visible Skip and a Start action.
- No onboarding gating, account profiling service or extra profile menu items. Interface remains Russian.
- Library cards retain the compact stripe/title/meta composition. Imported files and demo examples have explicit, different statuses.
- Start/end timestamps are placed on a separate compact line next to the speaker content to avoid cramped horizontal rows.
- No export preview card. No new language settings. Essential demo/privacy/error notices never depend on the hint preference.
- No WebView, 3D rendering or video animation. File copying uses a 64 KiB buffer rather than reading an entire recording into RAM.

Visual QA limitation: only launcher asset masks were inspected in this environment. Full screen rendering and font-scale behavior require the device checklist.
