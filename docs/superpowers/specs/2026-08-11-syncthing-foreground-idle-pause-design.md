# Syncthing foreground-only idle pause

## Goal

Syncthing may be paused for idle power saving only while KKCSheetTracker is foregrounded. Moving the app to the background must leave Syncthing running.

## Design

`MainActivity` reports foreground state to `SyncthingSupervisor`: foreground at `onStart`, background at `onStop`.

The supervisor combines that state with the observed idle phase. An idle pause is desired only when both conditions hold:

1. the app is foregrounded; and
2. the idle phase is `SYNC_PAUSED`.

When the app backgrounds, the supervisor clears the idle-pause request and resumes Syncthing if an idle pause had completed. Returning to the foreground immediately reconciles the current idle phase: it pauses only if that phase remains `SYNC_PAUSED`; otherwise it leaves Syncthing running.

The behavior is confined to the supervisor’s existing desired-versus-actual pause reconciliation. It does not alter the idle theme, PDF behavior, polling, watchdog health checks, API-key handling, or normal manual Syncthing controls.

## Verification

Add focused supervisor tests for:

- no pause when the idle phase reaches `SYNC_PAUSED` in the background;
- immediate resume when a foreground idle pause backgrounds; and
- re-entry to the foreground pausing only when the current phase is still `SYNC_PAUSED`.

Run the focused supervisor tests, the full debug unit suite, and `assembleRelease` before deployment.
