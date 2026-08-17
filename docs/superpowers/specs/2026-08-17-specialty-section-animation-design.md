# Specialty section animation

## Goal

Animate specialty job sections open and closed without the oversized gaps that
appear when a collapsed row remains a `LazyColumn` interval.

## Design

Keep each checklist and sheet-rip row as an individual lazy item. This retains
the current stable keys and scales to large sections. Restore its
`AnimatedVisibility` with a 300 ms vertical-size and fade transition.

Remove `LazyColumn`'s global `Arrangement.spacedBy(12.dp)`. Instead, apply the
12 dp separation only to visible top-level content and section headers. The
rows keep their existing explicit bottom padding for row-to-row separation.

With no global lazy-item spacing, a row that has shrunk to zero height adds no
space. The next section header therefore stays 12 dp below the preceding
visible header when a section is collapsed.

## Scope

Change only `SpecialtyJobDetailScreen`. Preserve sticky section headers,
existing expanded-section preferences, item keys, progress controls, and row
content.

## Verification

Run the specialty unit suite and a debug build. Verify visually on a tablet
only if an APK signed compatibly with its installed app is available; do not
uninstall the tablet app to bypass a signature mismatch.
