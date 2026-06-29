# Migration Proposals

The loop **never executes** a large migration. When a pass spots a change with outsized optimization
payoff that crosses the medium/high-risk line (new dependency, data-format change, architecture or
threading change, websockets, Room, WorkManager, Paging, etc.), it writes a proposal here and keeps
going. The owner reviews and decides separately.

One file per proposal: `migrations/<short-name>.md`, using the template below.

---

## Template

```markdown
# Migration: <name>

## Summary
One paragraph: what the change is, what it replaces.

## Why recommended
The concrete problem it solves and the optimization payoff. Cite the evidence found
(file:line, measured/observed behavior) — not speculation.

## Expected impact
- Performance / UX gain (be specific: what gets faster, by roughly how much, where).
- Who benefits (which screens / shop workflows).

## Difficulty / effort
- Rough size (files touched, new deps, est. complexity).
- Skill/risk required.

## Blast radius
- What it touches: data formats, sync correctness, public APIs, threading, UI.
- What could break if done wrong.

## Rollback
- How to back it out if it goes wrong.

## Recommendation
Do it / defer / not worth it — and why.
```
