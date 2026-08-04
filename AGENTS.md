# KKCSheetTracker — Development Notes

## Project
Android app for KKC Custom Cabinets. Tracks sheet materials, jobs, and employee time.
Two shop locations connected via Omada site-to-site VPN.

## Timeclock Feature

### Architecture
- Hub server (`C:\Scripts\timeclock-hub\`) runs in Docker on TrueNAS Scale
- Polls one RTC-1000 device every 3 minutes; SQLite is the source of truth
- Android tablets talk to the hub via REST (mDNS auto-discovery or manual IP)
- Per-tablet background config stored in DataStore (`timeclock_background` prefs file)

### Frosted Glass Buttons — DO NOT use Surface + shadowElevation or semi-transparent background with shadow
Using `Surface(shadowElevation)` or `Modifier.shadow()` + `background(color.copy(alpha < 1f))` causes
the shadow to bleed through the transparent fill as a dark inner ring. This is a fundamental Android
hardware compositing issue — the compositor draws the shadow behind the layer and it shows through.

**The correct pattern for frosted semi-transparent elements:**
```kotlin
// hazeSource must be on the background layer (TimeclockBackground)
// Elements on top use hazeEffect — fills at full opacity so shadow cannot bleed through
modifier
    .shadow(elevation, shape, clip = false)   // external shadow only
    .clip(shape)
    .hazeEffect(state = hazeState, style = HazeDefaults.style(
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        blurRadius = 14.dp
    ))
```

For solid-color elements (action button): use `shadow(clip = false)` + `clip()` + `background(solidColor)`.
Never use `Surface(shadowElevation)` with any semi-transparent color — same bleed issue.

### hazeState wiring
`hazeState` lives in `TimecardScreen` and is applied to `TimeclockBackground` as `.hazeSource()`.
It flows down: `TimecardScreen` → `TimecardReadyState` → `NumpadGrid` → `NumpadKey`.
DisplayCard also receives it. Do NOT re-create a local hazeState inside `TimecardReadyState`.

### Hours display
Format with `"%.2f"` (two decimal places), never `"%.1f"`. Hub rounds up to nearest 15 minutes.

### Punch business rules
- Duration rounds UP to nearest 15-minute increment (`math.ceil(minutes / 15) * 15 / 60`)
- Punches under 7 minutes are deleted silently (accidental clock-in/out)
- Hub timezone: `TZ=America/Los_Angeles` in docker-compose — handles DST automatically

## Build
```
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Development & Deployment Rules
- **NEVER** run `adb uninstall` or uninstall the app from connected devices without explicit user permission.

## Imported Claude Cowork project instructions

---

## Installed Codex Workflow

+
## Core Design Principles

The project must strictly follow modular design.

Each module should have:

- A clear responsibility.
- A clear interface.
- Minimal unnecessary coupling.
- A structure that makes it easy to test, debug, replace, extend, and reuse.

Nested modules are allowed when they make responsibilities clearer. Avoid placing unrelated responsibilities into the same file, class, service, or large function.

- Define proportionate acceptance and verification requirements before implementation.
- Keep related tests cohesive enough to avoid fragmented micro-tests, but never reduce meaningful coverage, weaken assertions, or hide failures merely to save tokens or execution time.

### Project-specific principles

- Use the languages and platforms already established by the project unless a change is explicitly approved.
- Add meaningful automated coverage for behavior changes and do not weaken existing checks to make them pass.
- Keep tablet workflows offline-safe and preserve clear operator-facing recovery paths.
- Do not introduce a new external service or deployment dependency without explicit approval.
- Preserve one canonical owner for each Ready Jobs metadata stream; derived JSON remains a durable recovery representation.


## Tool Execution and Batching

For each bounded work stage, identify independent, already-known, non-conflicting tool calls before invoking tools. When practical, execute them through one outer `functions.exec` or Code Mode `exec` call.

Use `Promise.allSettled()` when successful results remain useful even if another call fails. Inspect and attribute every returned result. Use `Promise.all()` only when any individual failure invalidates the entire batch.

Prefer batching for:

- Read-only file inspection.
- Independent symbol, text, and call-site searches.
- Repository metadata and status collection.
- Independent log or artifact inspection.
- Validation commands that do not share mutable state.

Keep operations sequential when they involve:

- A result that determines the next operation.
- Adaptive investigation where the next target is not yet known.
- Approvals or permission boundaries.
- Agent spawn, wait, resume, message, or replacement operations.
- Overlapping or order-sensitive writes.
- Git staging, commits, resets, or other Git-state mutations.
- Builds or tests sharing a build directory, generated output, database, port, fixture, device, or other mutable resource.

Do not split an otherwise batchable inspection across repeated outer tool calls. Do not create extra work, broaden scope, obscure failure attribution, or increase worker count merely to fill a batch.

Tool-call concurrency is local to one agent thread. It does not change route selection, worker ownership, scope boundaries, verification requirements, or subagent-concurrency limits. A stage requiring only one useful tool call should remain one call.

## Working State

At any given time, we will be in one of two working states:
- `deployment state`: beginning to plan a broad task or in the process of deploying a plan. A  deployment plan can span multiple sessions.
- `leaf state`: for tasks outside the plan being deployed by the `deployment state`, such as general queries, document editing, or performing operations to add, modify, or delete small files, modules, or tools.

## Project Documentation Framework

The main project documents are stored under `agent_docs/`:

- `agent_docs/project_overview.md`: goals, architecture, workflow, and major decisions.
- `agent_docs/project_core_tech.md`:A brief summary of special technologies or architectures of project.
- `agent_docs/project_structure.md`: directory layout, modules, components, and ownership boundaries.
- `agent_docs/project_progress.md`: active implementation plan and cross-session execution status.
- `agent_docs/project_diary.md`: durable architecture decisions, discarded approaches, and lessons.
- `agent_docs/latest_session_work.md`: Summarizing previous sessions along with any unfinished tasks.
- Module-specific documents, when present.

--------
`agent_docs/project_progress.md` and `agent_docs/latest_session_work.md` are two documents designed to ensure smooth and seamless deployment between multiple sessions in deployment mode. These two files can only be edited in `deployment state` or when the user explicitly requests it. The main agent is responsible for updating these two files, while subagents are not allowed to edit them.

Update documentation only with verified facts. Keep temporary reasoning, raw logs, and short-lived checkpoints out of durable project documents.

Never delete any main project document without warning the user and receiving a second explicit confirmation.

## Route Selection

There are three routes.
### Light route: 
Use for light tasks which in the `leaf state`.
Performs tasks by yourself. Do not spawn subagents in this route.

### Medium route: 
Use for deploying large tasks/plans in the `deployment state`.
Performs tasks by yourself. Do not spawn subagent in this route
Read and follow `agent_docs/workflow/medium_route.md`.

### Heavy route: 
You a orchestrator, coordinates subagents to deploy large tasks/plans in the `deployment state`.
Read and follow `agent_docs/workflow/heavy_route.md`.

### Route selection rules and state interpolation

The route will be specified by the user, like: "use Light/medium/heavy route...". Apply that route throughout the entire session until it ends or until the user indicates to switch to the other route. If the user does not specify a route, select the light route as the default. Do not guess and choose a route yourself.

If the light route is specified or choosed, it means we are in the `leaf state`. 
If the medium route/heavy route is specified, it means we will proceed to the `deployment state`. 

## Context Loading

- In the Light route (`leaf state`), read only the files relevant to the current task.
- On first entering the `deployment state`, load the foundational project context in one bounded read-only batch:
  1. `agent_docs/project_overview.md`
  2. `agent_docs/project_structure.md`
  3. `agent_docs/project_progress.md`
  4. `agent_docs/latest_session_work.md`
- After the batch returns, interpret overview and structure before reconciling progress and the latest-session handoff. This interpretation order does not require separate outer tool calls.
- Use the resulting status and ownership map to inspect the smallest relevant interfaces, call sites, tests, and configuration surface.
- Read only relevant module documentation. Expand source inspection only when repository evidence requires it.
- Reconstruct active tasks, dependencies, verification state, and blockers. Resolve contradictions with targeted evidence.
- Under the Heavy route, review only critical hunks and integration boundaries after delegation unless risk, missing evidence, or conflicting results require broader inspection.

## Platform-specific paths

Paths in this workflow are written using `/` as a platform-neutral separator.
When running filesystem commands, use paths appropriate for the current operating system and shell:

* On Linux and macOS, use `/`.
* On Windows, use the equivalent Windows path format and `\` where required.

Do not treat the example path separator as a literal requirement. Resolve every path using the conventions of the current environment.
