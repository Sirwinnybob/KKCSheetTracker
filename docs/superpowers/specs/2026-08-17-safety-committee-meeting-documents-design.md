# Safety Committee Meeting Documents Design

## Goal

Add a read-only Safety Committee Meetings document library to the Safety / SDS page. The library must appear as the middle tab and read PDF files from `.safety/safety_meetings` under the configured network-share root.

## User Experience

The Safety / SDS page will show three tabs in this order:

1. Documents (PDFs)
2. Safety Committee Meetings
3. Safety Concerns

The meeting tab lists PDF files by filename in ascending order. Selecting a file opens it with the same Android PDF-viewer flow used by the existing Documents tab. If the folder is missing or contains no PDFs, the tab displays `No safety committee meeting documents found.`

Only regular files with a `.pdf` extension, matched without regard to case, are shown. Subdirectories and other file types are ignored.

## Architecture and Data Flow

`SafetyDocumentsScreen` will resolve two read-only document folders from `basePath`:

- General documents: `.safety`
- Meeting documents: `.safety/safety_meetings`

The existing pure PDF-listing logic will be reused for both folders. Screen refresh will load both lists on the IO dispatcher alongside the existing Safety Concerns refresh.

A small reusable Compose document-list component will render the common empty state, file rows, and click behavior for both PDF tabs. Each tab supplies its own file list and empty-state message. This keeps file-opening behavior consistent without introducing a broader repository abstraction.

The Safety Concerns tab moves from index 1 to index 2. Its access rules, feed, dialogs, reporting workflow, and existing in-progress admin-access changes remain unchanged.

## Error Handling

A missing or unreadable meetings folder produces an empty list and the normal empty-state message. Failures from the external PDF-viewer launch continue to be logged through the existing error path without crashing the Safety page.

## Testing and Verification

Focused unit coverage will verify that meeting documents are resolved from `.safety/safety_meetings`, that only PDF files are returned, and that ordering is stable. Existing Safety screen logic tests must continue to pass.

Verification will include the focused unit-test class and a debug APK build. No tablet installation or application uninstall is part of this change.

## Acceptance Criteria

- The page has exactly three tabs in the required order.
- The middle tab reads PDFs only from `<network-share>/.safety/safety_meetings`.
- Meeting PDFs open through the existing viewer intent.
- Missing or empty meeting storage produces a clear empty state.
- Safety Concerns behavior is preserved at the third tab.
- Focused tests and `assembleDebug` pass.
