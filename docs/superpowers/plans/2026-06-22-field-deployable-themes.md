# Field-Deployable Theme System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Compose-only field themes loaded from synced Ready Jobs content with a synced default and local tablet override.

**Architecture:** Theme JSON files live under `{basePath}/.metadata/themes`, are parsed into validated definitions, converted to runtime Compose tokens, and supplied by `KKCTheme`. Settings exposes the active synced default and local override controls.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Gson, SharedPreferences, JUnit.

---

## Tasks

- [ ] Add parser/repository tests for valid themes, invalid themes, unknown keys, active fallback, and local override precedence.
- [ ] Implement `KKCThemeDefinition`, `KKCThemeTokens`, `KKCThemeRepository`, and SharedPreferences-backed override storage.
- [ ] Wire `KKCTheme` to provide runtime tokens and derive Material3/status/shape values from active tokens.
- [ ] Integrate repository resolution in `MainActivity`.
- [ ] Add Settings UI for synced default, local override, and invalid theme status.
- [ ] Migrate shared primitives to consume semantic tokens where this can be done without behavior changes.
- [ ] Verify timeclock frosted surface guardrail and run `.\gradlew.bat test` plus `.\gradlew.bat assembleDebug`.
