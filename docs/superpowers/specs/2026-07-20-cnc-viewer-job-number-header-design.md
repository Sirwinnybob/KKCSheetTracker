# CNC Viewer Job Number Header

## Goal

Show the job number before the material name in the CNC sheet viewer header.

Example: `12345 - Maple`.

## Scope

Only the CNC `SheetViewerScreen` top app bar title changes. PDF filenames, navigation routes, job lists, and headers on other screens remain unchanged.

## Design

`SheetViewerScreen` already receives the current job folder and has access to the scanned job list. Find the job whose `folderName` matches `jobFolderName`, then use its structured `jobNumber` for the title. The title is:

- `jobNumber - materialName` when the matching job has a nonblank job number.
- The existing `materialName` alone when the job is not yet available or its number is blank.

The existing material-name derivation remains unchanged.

## Verification

- Confirm the title construction is limited to the CNC viewer top bar.
- Run the focused viewer tests if applicable.
- Run `./gradlew.bat assembleDebug` from the repository root.
- Preserve unrelated working-tree files.
