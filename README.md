# Portal

Portal is a capture-first Android notes app for fast text, photos, audio, links, and lightweight classification.

This project is built from a Markor fork and has been reshaped into a focused capture workflow with a custom Portal interface, media attachments, share-into flows, and a dedicated session model.

## What Portal does

- Starts on a fast note capture screen instead of a file browser
- Saves each note as its own session with text plus attachments
- Supports titles, note types, tags, Markdown formatting, and render preview
- Lets you attach images from the Android photo picker or record/import audio
- Shows up in the Android share menu for text, links, and images
- Keeps data in plain files on device storage

## Default storage layout

By default, Portal uses:

- `Documents/Portal/Portal Sessions`
- `Documents/Portal/Portal Drafts`
- `Documents/Portal/Portal Outbox`

Audio and image attachments are stored inside each session folder under `assets/`.

Example:

```text
Documents/Portal/Portal Sessions/2026-03-07_22-23-03__quick-note/
├── 2026-03-07_22-23-03__quick-note.md
└── assets/
    ├── image-2026-03-07_22-23-45.jpg
    └── audio-2026-03-07_22-24-10.m4a
```

## How to use Portal

### 1. Create a note

- Launch Portal
- Start typing in the main note area
- Optionally add a title
- Pick a note type such as `Quick Note`
- Add tags with the `+` chip

### 2. Format text

Portal includes one-tap formatting for:

- H1 / H2
- Bold / Italic / Underline
- Bulleted and numbered lists
- Quote
- Code block
- Link formatting

List behavior continues bullets or numbers on newline until you clear the marker.

### 3. Attach media

- Tap the gallery button to open the native Android photo picker directly
- Tap the camera button to capture a photo
- Tap the mic button to start/stop recording audio
- Long-press the mic to open the in-app audio file browser and attach an existing audio file

Attachments render as native cards in edit mode and as large cards in preview mode.

### 4. Preview before sending

- Tap the eye icon to switch into render preview
- Preview keeps the Portal layout instead of jumping to a separate screen
- Images and audio render as cards instead of raw Markdown

### 5. Share into Portal

Portal accepts Android shares for:

- plain text
- links
- images

Shared links create a new Portal note and preserve the link content as a hyperlink-ready note body.

## Note types and tags

- Note types are managed from the right-side picker
- Long-press a note type to delete it
- Long-press a tag to delete it
- Default note types can be hidden and restored

## Settings

Portal settings currently focus on storage roots:

- `Send Folder`
- `Draft folder`

The settings panel uses the same floating card language as the rest of the app.

## Development

### Build

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_SDK_ROOT=/Users/mgildner/Library/Android/sdk \
GRADLE_USER_HOME=.gradle-home \
./gradlew --no-daemon assembleFlavorDefaultDebug -x lint
```

### Main launcher entry

- `net.gsantner.markor.portal.PortalEntryActivity`

### Launcher icon

The app launcher now uses the adaptive icon resources referenced from the manifest:

- `@mipmap/ic_launcher`
- `@mipmap/ic_launcher_round`

## Project status

This branch contains the first Portal-specific branding and UX pass toward an initial `1.0` release.

## License and attribution

Portal remains based on code originally distributed under Apache 2.0 and related resource licenses in this repository.

- See [LICENSE.txt](LICENSE.txt)
- Original copyright and attribution notices in source/resource files are preserved
- This fork changes branding and product direction, but does not remove original license obligations
