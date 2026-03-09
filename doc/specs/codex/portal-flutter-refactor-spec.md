# Portal Flutter Refactor Technical Spec

## 1. Title Page

- Spec: `codex/portal-flutter-refactor-spec`
- Project: Portal
- Scope: Refactor the current Android/Java Portal experience into a modern Flutter + Dart architecture while preserving on-device plain-file storage and current capture workflows
- Status: Planning
- Intended release vehicle: multi-phase migration, not a single cut-over

## 2. Purpose and Scope

Portal has evolved from a Markor fork into a capture-first notes app with a distinct UX, but the current implementation is still centered around a large Android `Activity` with tightly coupled UI, state, file IO, media handling, share import, and preview behavior.

This spec defines a refactor plan to move Portal to:

- Flutter for UI and feature composition
- Dart domain/data layers with testable use cases
- a thin Android host layer for OS integrations
- a migration path for future on-device Whisper transcription and auto-tagging

### In scope

- Flutter-based replacement for the current Portal capture and session browser flows
- modern app architecture and module boundaries
- durable local storage model and export/send behavior
- share-into support, audio/image attachments, preview rendering, tags, note types, and settings
- explicit future extension points for local Whisper transcription and auto-tagging

### Out of scope

- full rewrite of all legacy Markor features outside the Portal experience
- package namespace rename in the first migration wave
- server-backed sync or cloud APIs
- remote LLM tagging/transcription

## 3. Background and Context

### Current Portal implementation

Current Portal logic lives primarily in:

- [PortalEntryActivity](/Users/mgildner/vault/vault_dev/portal/app/src/main/java/net/gsantner/markor/portal/PortalEntryActivity.java)
- [PortalInputActivity](/Users/mgildner/vault/vault_dev/portal/app/src/main/java/net/gsantner/markor/portal/PortalInputActivity.java)
- [PortalStorage](/Users/mgildner/vault/vault_dev/portal/app/src/main/java/net/gsantner/markor/portal/PortalStorage.java)
- [PortalSessionRepository](/Users/mgildner/vault/vault_dev/portal/app/src/main/java/net/gsantner/markor/portal/PortalSessionRepository.java)
- [PortalShareImport](/Users/mgildner/vault/vault_dev/portal/app/src/main/java/net/gsantner/markor/portal/PortalShareImport.java)

### Current architecture summary

- `PortalEntryActivity` resolves launch/share intents, creates a session folder, seeds note content from a share, and routes into the main input screen.
- `PortalInputActivity` owns nearly all feature logic:
  - editing
  - preview mode
  - note type/tag state
  - media picking and recording
  - publish/send behavior
  - settings surface
  - animation and drawer handling
- `PortalStorage` defines filesystem roots and session folder creation.
- `PortalSessionRepository` reads/writes markdown session files and enumerates sessions.
- Markdown preview uses Android WebView and Flexmark.

### Current storage model

Default Portal directories:

- `Documents/Portal/Portal Sessions`
- `Documents/Portal/Portal Drafts`
- `Documents/Portal/Portal Outbox`

Each session is stored as:

- one markdown file
- one sibling `assets/` directory for attachments

### Migration motivation

- Current UI/state logic is hard to test and expensive to evolve.
- Android-specific UI code is monolithic and brittle.
- Portal now has product-specific behavior that benefits from a clean domain model.
- Future ML features need a controlled background-job and metadata pipeline.

## 4. Assumptions and Constraints

| Assumption / Constraint | Notes |
|---|---|
| Android remains the primary runtime target | iOS/web/desktop are not part of initial delivery |
| Existing plain-file storage remains the source of truth | Session markdown + `assets/` directories stay user-accessible |
| Package continuity is preferred in early phases | Avoid breaking installs/share targets until explicitly planned |
| Offline-first behavior must remain | No network dependency for core note creation/editing |
| Current Portal UX should be preserved functionally, then improved | Refactor should not regress capture speed |
| Whisper transcription must run locally in a future phase | No cloud transcription dependency |
| Tagging should remain explainable and deterministic where possible | Auto-tag suggestions must be debuggable |

## 5. Functional Requirements

| ID | Requirement | Priority | Rationale | Verification |
|---:|-------------|----------|-----------|--------------|
| FR-1 | The app shall launch into a Flutter-based Portal capture screen that replaces `PortalInputActivity` behaviorally | P0 | Main user path | Manual launch test; screenshot parity checklist |
| FR-2 | The app shall preserve the current session-based file model: one markdown note file plus `assets/` directory per session | P0 | Preserve user data layout | File inspection test against created session |
| FR-3 | The app shall preserve note title, note type, tags, markdown body, image attachments, and audio attachments | P0 | No data loss in migration | Round-trip create/read/edit regression suite |
| FR-4 | The app shall preserve share-into flows for text, links, and images | P0 | High-frequency entry path | Android share intent instrumentation tests |
| FR-5 | The app shall preserve swipe-to-send export behavior into the configured Send Folder while retaining the working copy in Sessions | P0 | Core publish behavior | Emulator/manual file export verification |
| FR-6 | The app shall provide render preview for markdown and attachment cards in Flutter | P0 | Current key workflow | Golden/UI tests and manual preview parity |
| FR-7 | The app shall provide a Flutter session browser for searching and reopening prior sessions | P1 | Existing browse flow | Manual reopen/search tests |
| FR-8 | The app shall preserve direct gallery attach, camera capture, audio recording, and audio import | P1 | Core media workflow | Manual picker/recording tests |
| FR-9 | The app shall preserve configurable Send Folder and Draft Folder settings | P1 | Existing settings flow | Manual settings flow test |
| FR-10 | The app shall support future local transcription jobs attached to audio assets without changing the core session file layout | P1 | Future ML roadmap | Architecture review + integration test plan |
| FR-11 | The app shall support future auto-tag suggestion generation from note text/transcripts | P1 | Future ML roadmap | Domain contract tests |
| FR-12 | The app shall support delete/restore operations for note types and tag deletion in the Flutter UI | P2 | Existing behavior | Widget test + repository test |

## 6. Non-Functional Requirements

| ID | Requirement | Priority | Rationale | Verification |
|---:|-------------|----------|-----------|--------------|
| NFR-1 | Cold start to editable capture screen should not regress beyond current baseline by more than 15% on the target emulator/device class | P0 | Capture speed matters | Benchmark comparison |
| NFR-2 | Editing and scrolling shall remain responsive for typical note sizes (< 50 KB note body, < 20 attachments) | P0 | UX quality | Manual stress runs + profile trace |
| NFR-3 | File writes shall be atomic enough to avoid corrupting session files during app interruption | P0 | Data safety | Crash/interruption test |
| NFR-4 | Architecture shall isolate platform APIs behind adapters/plugins | P0 | Maintainability | Code review against module boundaries |
| NFR-5 | Domain and data layers shall be unit-testable without Android runtime | P0 | Refactor safety | Dart unit test coverage |
| NFR-6 | Transcription/tagging jobs shall be cancellable and resumable at the app level | P1 | Future ML robustness | Background job integration tests |
| NFR-7 | Storage migration shall be backward-compatible with existing Portal session directories | P0 | Existing users must keep notes | Migration validation script |
| NFR-8 | The new architecture shall preserve license and attribution notices where required | P0 | Compliance | Release checklist review |

## 7. Architecture Overview

### Target architecture

Use a layered Flutter architecture:

1. `app_shell_android`
   - Android host app
   - manifest, share intent entrypoints, permission prompts, widgets/shortcuts, notifications
   - hosts Flutter engine/activity

2. `portal_flutter_app`
   - Flutter UI layer
   - navigation, theming, widgets, animations, screen composition

3. `portal_domain`
   - pure Dart entities, value objects, use cases
   - no Flutter or Android dependencies

4. `portal_data`
   - repositories, mappers, local index/db, file adapters, transcription/tagging orchestration

5. `portal_platform`
   - platform channel or FFI wrappers for:
     - audio recording
     - camera/photo picker
     - share intents
     - filesystem/document pickers if needed
     - app widget / launcher integrations if retained

### Recommended Flutter stack

- State management: Riverpod
- Navigation: GoRouter
- Immutable models: Freezed + json_serializable
- Local metadata DB: Drift or Isar
- Audio playback: just_audio
- Audio waveform UI: audio_waveforms or custom painter
- Permissions: permission_handler
- Photo picker / camera: image_picker or photo_manager + camera
- Share targets: Android host + method channel handoff into Flutter
- Markdown preview:
  - primary path: Flutter markdown/html renderer
  - fallback path for parity-sensitive content: Flutter WebView wrapper only where necessary

### Recommended migration shape

- Phase 1: introduce Flutter host and Flutter Portal capture screen in parallel
- Phase 2: migrate session browser, settings, share flows
- Phase 3: remove Java Portal UI paths
- Phase 4: add transcription/tagging pipeline

## 8. Data Models

### Core Dart entities

```text
Session
- id
- sessionDirectoryPath
- noteFilePath
- title
- noteTypeSlug
- tags[]
- bodyMarkdown
- createdAt
- updatedAt
- attachmentCount

Attachment
- id
- sessionId
- path
- type(image|audio)
- mimeType
- createdAt
- waveformSummary?
- durationMs?
- transcriptStatus?

SendExport
- sessionId
- exportedPath
- exportedAt
- status

Transcript
- attachmentId
- engine(whisper_cpp)
- status(pending|running|done|failed)
- text
- segments[]

TagSuggestion
- source(body|transcript|hybrid)
- tag
- confidence
- accepted
```

### Storage principle

- markdown file remains user-facing source of note content
- structured metadata/index is additive, not authoritative over the file
- repository must be able to reconstruct state from disk if local index is lost

## 9. APIs / Interfaces

### Repository interfaces

| Interface | Responsibility |
|---|---|
| `SessionRepository` | Create, read, update, list, export sessions |
| `AttachmentRepository` | Add/remove/list attachments and derived metadata |
| `TagRepository` | Top tags, tag deletion, tag usage tracking |
| `NoteTypeRepository` | Default/custom note type visibility and CRUD |
| `SettingsRepository` | Send Folder, Draft Folder, theme, preview prefs |
| `ShareImportRepository` | Transform Android share payloads into seeded sessions |
| `TranscriptionRepository` | Queue, run, persist local whisper transcription jobs |
| `AutoTagRepository` | Produce tag suggestions from note body/transcript |

### Platform channel surfaces

| Channel | Methods |
|---|---|
| `portal/media` | `pickImages`, `capturePhoto`, `startAudioRecording`, `stopAudioRecording` |
| `portal/share` | `getInitialSharePayload`, stream later share payloads |
| `portal/filesystem` | `pickDirectory`, optional document picker fallback |
| `portal/system` | launcher/widget hooks if kept |

### Example share payload from Android host to Flutter

```json
{
  "action": "send",
  "mimeType": "text/plain",
  "text": "https://example.com/article",
  "subject": "Shared link",
  "uris": []
}
```

### Example export workflow

1. User swipes to send.
2. Flutter use case persists current session.
3. `SessionRepository.exportSession(sessionId)` copies the session folder into Send Folder.
4. UI shows animated confirmation and starts a fresh working session.

## 10. Error Handling

| Scenario | Expected behavior |
|---|---|
| Storage root unavailable | Show recoverable error with settings shortcut |
| Attachment copy failure | Preserve note text, surface inline error, do not corrupt session |
| Send export failure | Keep working session intact, show error, do not start a fresh session automatically unless explicitly desired |
| Share import parse failure | Create a plain text fallback note with raw shared content |
| Transcription failure | Persist failure status and allow retry |
| Auto-tag failure | No blocking UI failure; omit suggestions and log diagnostic event locally |

## 11. Testing Strategy

### Unit tests

- Dart domain/use-case tests for:
  - session creation
  - tag/note type management
  - export to Send Folder
  - share import mapping
  - transcription job state machine
  - auto-tag suggestion acceptance

### Widget tests

- capture screen
- note type drawer
- tag chip row
- settings card
- attachment cards
- send animation trigger

### Integration tests

- Android share into Portal
- create note + attach image/audio + preview + send export
- reopen session from browser
- settings folder changes

### Migration validation

- seed the app with existing `Documents/Portal/Portal Sessions` data
- verify Flutter app loads current sessions without rewriting them
- compare attachment rendering and preview with current Portal behavior

## 12. Rollout / Migration Plan

### Phase 0: Spec and architecture groundwork

- agree on migration scope
- choose Flutter package/module layout
- freeze current Portal behavior with acceptance screenshots/tests where practical

### Phase 1: Flutter host + shell

- embed Flutter in the Android app
- preserve current package/app id
- route launcher into Flutter shell behind a feature flag if needed

### Phase 2: Capture screen migration

- migrate `PortalInputActivity` behavior into Flutter
- keep Java host only for entrypoint wiring and platform adapters

### Phase 3: Session browser + settings + share flows

- migrate browser/settings/share handling into Flutter
- retain Android manifest intent filters in the host

### Phase 4: Decommission Java Portal UI

- remove Java Portal presentation code once parity is reached
- keep only thin platform integration layer

### Phase 5: Local ML

- integrate Whisper inference engine locally
- add transcript storage and tag suggestion pipeline

## 13. Future Whisper and Auto-Tagging Plan

### Local Whisper transcription target

Recommended implementation path:

- Android native library using `whisper.cpp`
- expose inference through platform channel or FFI bridge
- queue transcription jobs for audio attachments after recording/import

Why:

- mature local Whisper path on mobile-class hardware
- avoids network dependency
- allows incremental model sizing (tiny/base) per device capability

### Auto-tagging target

Start with a deterministic + local heuristic pipeline:

- keyword extraction from note body
- keyword extraction from transcript
- configurable stopword/domain dictionaries
- ranking against existing top tags + note type context

Later optional path:

- small on-device text classifier or embedding model for suggestions

### ML requirements

| ID | Requirement | Priority | Rationale | Verification |
|---:|-------------|----------|-----------|--------------|
| ML-1 | Transcription must run fully on-device | P1 | Privacy/offline requirement | Network-off device test |
| ML-2 | Transcript text must be stored as additive metadata, not destructive note rewrite | P1 | Preserve user content model | Storage integration test |
| ML-3 | Auto-tagging must present suggestions, not silently mutate user tags by default | P1 | User trust | UI test + review |
| ML-4 | Long audio processing must be resumable/retryable | P2 | Robustness | Job retry integration test |

## 14. Assumptions and Open Questions

### Assumptions

- Android-first migration is acceptable
- package id continuity is preferred over immediate namespace cleanup
- existing Portal Sessions / Drafts / Outbox layout should remain
- direct file visibility in `Documents/Portal` is a core product value

### Open Questions

1. Should the Flutter refactor live inside the existing Android app module first, or in a parallel new app module with a migration bridge?
2. Should markdown preview be rendered natively in Flutter or temporarily preserved with a WebView parity layer?
3. Should Send Folder export remain copy-only forever, or eventually support move/archive policies?
4. Should transcription results be appended into note bodies, stored only as metadata, or user-configurable?
5. What is the acceptable on-device model size/runtime budget for Whisper on mid-range Android hardware?

## 15. Definition of Done

- [ ] Flutter architecture skeleton exists with clear presentation/domain/data/platform boundaries
- [ ] Portal capture workflow works in Flutter with parity for text, tags, note types, attachments, preview, and send export
- [ ] Existing Portal sessions load without migration loss
- [ ] Android share intents route correctly into the Flutter experience
- [ ] Settings roots remain configurable and honored
- [ ] README/developer docs explain the new architecture and migration approach
- [ ] Local transcription/tagging extension points are implemented or stubbed with stable interfaces
- [ ] Unit, widget, and integration tests cover the critical capture and export paths
