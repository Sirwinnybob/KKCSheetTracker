# CNC Mix Live Operation Design

## Goal

Let a tablet submit Manage Code work without holding its screen open, while showing honest live progress for the selected job and retaining the final result when the operator returns.

## Service operation model

The PGM Mix Service will own a durable operation registry beside its existing definitions data. A submitted asynchronous mix write or PGM edit returns HTTP 202 with an immutable operation ID instead of keeping the HTTP request open. The service performs the work in its own worker thread and publishes snapshots through `GET /operations/{id}` and a job-filtered list endpoint.

Each snapshot includes the operation ID, job, material, kind, terminal state, stage, timestamps, optional error, completed program count, and total program count. States are `queued`, `running`, `completed`, `failed`, and `interrupted`; stages are `queued`, `preparing`, `compiling`, and `syncing`. Preparation reports exact program counts. Compilation remains explicitly indeterminate because WINXISO does not report a reliable internal percentage. Operation records are persisted on every state transition. On service startup, any nonterminal record is marked `interrupted`; it is never silently replayed.

No prior tablet release consumes these endpoints. Mix and PGM-edit mutations therefore become asynchronous by default: their existing write routes return HTTP 202 with an operation ID, and the tablet follows the operation to its terminal result.

## Tablet operation session

An application-scoped coordinator, not the Compose screen's coroutine scope, owns the ordered Manage Code session for each job. It persists the queued material changes and server operation IDs in DataStore. It polls active operations, starts the next queued action only after the current operation reaches a terminal state, and restores polling after process recreation. Leaving Manage Code changes nothing about the session. Returning to the same job reattaches to the persisted state and refreshes the material catalog only after completed changes.

## Operator experience

The Manage Code action becomes a disabled progress button whenever that job has a session. It displays completed-material count and the current stage. During preparation it has determinate progress; during WINXISO compilation it switches to an indeterminate bar and says that it is compiling. Failures stay visible with a Retry action; an interrupted operation requires an explicit retry. Sessions for other jobs are not shown as active work on the current job.

## Safety and verification

The coordinator must never automatically resubmit a nonterminal or interrupted operation, preventing accidental duplicate replaces. Service unit/integration tests will cover operation lifecycle, persisted restart interruption, and compatibility. Tablet tests will cover response parsing, persistent queue restoration, screen-independent completion, and job-specific progress selection.
