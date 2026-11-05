# Blue MIX creation and sheet pairing fix

**Goal:** Repair remaining MIX creation, verify new/split creation, and show each physical A/Z sheet once.

**Approach:** Reuse the service's existing revision-checked split transaction to copy a subset into a remaining MIX, supplying the unchanged source entries as the original. This preserves hidden units, parameter-file data, overrides, counts, and source revision checks. Skip rewriting a byte-identical original in the service. Keep ordinary new MIX creation by bare PGM name. Reject empty drafts before submission. Deduplicate identical program sets in MIX sheet lists, preferring visible head pages while retaining continuation pages with unique programs.

**Constraints:** Preserve PGM bytes, completion events, originals, revision backups, machine guards, and offline/uncertain-write handling. Never submit a test mutation against a production material. Reuse the current clean checkouts; perform the bounded changes inline.

## Work

- [x] Regression tests: remaining copy serialization, Blue create/split/copy preservation, duplicate continuation rows, and empty draft validation.
- [x] Implement remaining draft source snapshot and split request; avoid redundant original write; deduplicate physical sheets and block empty saves.
- [x] Run focused tests, Python service suite, Android unit tests and release build; review changed code.
- [x] Deploy the verified Blue package with backup/config preservation; validate against disposable CNC fixtures.
- [x] Install and publish the signed tablet update; inspect the physical-sheet list and capture the result.

## Verification ledger

- Baseline: 74 profile integration and MIX edit tests pass.
- Reproduced current remaining-create format returning `invalid_entries` without a write.
- Before screenshot: `blue-mix-before.png` shows pages 5/6 and 7/8 offering the same A/Z pair twice. Metadata confirms even pages are hidden part-list continuations.
- New service regression fails because the clone transaction rewrites the unchanged original (mtime changes).
- Final verification: 511 service tests and 2,490 Android unit tests pass; signed release 9.1.08/90108 built and installed on R52W209W6RA. APK SHA-256 `0b622b4b8285584fc3ab46ada091f75b481f98d4ce429267a3f2e6280dda38c4`.
- Live disposable Blue tests passed for new MIX, remaining-copy, and actual split; A/Z order, counts, metric overrides, source revisions and all PGM hashes verified. Fixture archived outside CNC root in `DeploymentChecks/blue-mix-fix-20261105/verified-fixture`.
- After screenshot shows 8 physical sheets instead of 13 PDF pages, with one row per A/Z pair. Owner confirmed “worked great”. No production material was mutated by the verification.
- Published 9.1.08/90108 to the stable app feed at 09:24 Pacific on 2026-11-05; published APK hash matches the installed/reviewed release above. Fix committed as `be986db5`, merged to `main` as `ac49704f`. Service fix committed as `534ba81`, merged to `master` as `eb745b7`.
