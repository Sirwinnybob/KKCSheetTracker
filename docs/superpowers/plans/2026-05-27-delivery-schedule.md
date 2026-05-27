# Delivery Schedule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a weekly Mon–Fri × AM/PM delivery schedule that shop admins manage in kkc-admin and shop leads see as a compact always-visible widget at the top of the Android job board.

**Architecture:** The schedule is stored as `{basePath}/.metadata/delivery_schedule.json` on the shared network drive so both kkc-admin (write) and the Android app (read-only) can access it without HTTP. The kkc-admin Express server exposes GET/PUT/POST routes; its React client adds a top-level "Schedule" button that replaces the job content area with a 5-day editing grid. The Android app reads the JSON directly from the filesystem, renders a compact row widget above `JobBoardGrid`, and expands to a full-screen `Dialog` on tap.

**Tech Stack:** Express + TypeScript (server), React + Tailwind (admin client), Kotlin + Jetpack Compose (Android), Gson (Android JSON), Jest (server tests)

**Spec:** `C:\Scripts\KKCSheetTracker\docs\superpowers\specs\2026-05-27-delivery-schedule-design.md`

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `C:\Scripts\kkc-admin\server\src\types.ts` | Modify | Add `DeliveryJob`, `DeliverySlot`, `DeliverySchedule` interfaces |
| `C:\Scripts\kkc-admin\server\src\lib\deliveryScheduleStore.ts` | Create | Read/write schedule JSON on network drive |
| `C:\Scripts\kkc-admin\server\__tests__\deliveryScheduleStore.test.ts` | Create | Jest unit tests for the store |
| `C:\Scripts\kkc-admin\server\src\routes\deliverySchedule.ts` | Create | GET / PUT / POST reset route handlers |
| `C:\Scripts\kkc-admin\server\src\index.ts` | Modify | Register delivery-schedule router |
| `C:\Scripts\kkc-admin\client\src\types.ts` | Modify | Add client-side `DeliveryJob`, `DeliverySlot`, `DeliverySlots` |
| `C:\Scripts\kkc-admin\client\src\api.ts` | Modify | Add `deliverySchedule` API group |
| `C:\Scripts\kkc-admin\client\src\components\DeliveryScheduleView.tsx` | Create | Full-page 5-day editing grid with drag/move/reset |
| `C:\Scripts\kkc-admin\client\src\App.tsx` | Modify | Add "Schedule" button + `showSchedule` state |
| `app\src\main\java\com\kkc\sheettracker\data\models\DeliveryScheduleModels.kt` | Create | Kotlin data classes + constants |
| `app\src\main\java\com\kkc\sheettracker\data\DeliveryScheduleRepository.kt` | Create | Reads `delivery_schedule.json` from filesystem via Gson |
| `app\src\main\java\com\kkc\sheettracker\ui\components\DeliveryScheduleWidget.kt` | Create | Compact always-visible schedule row above job board |
| `app\src\main\java\com\kkc\sheettracker\ui\components\DeliveryScheduleDialog.kt` | Create | Full-screen detail dialog with addresses + map/copy actions |
| `app\src\main\java\com\kkc\sheettracker\ui\browser\JobBrowserScreen.kt` | Modify | Load schedule, show widget above grid, show dialog on tap |
| `app\src\main\java\com\kkc\sheettracker\navigation\NavGraph.kt` | Modify | Construct `DeliveryScheduleRepository`, pass to `JobBrowserScreen` |

---

### Task 1: Server types + store

**Files:**
- Modify: `C:\Scripts\kkc-admin\server\src\types.ts`
- Create: `C:\Scripts\kkc-admin\server\src\lib\deliveryScheduleStore.ts`
- Create: `C:\Scripts\kkc-admin\server\__tests__\deliveryScheduleStore.test.ts`

- [ ] **Step 1: Write the failing test**

Create `C:\Scripts\kkc-admin\server\__tests__\deliveryScheduleStore.test.ts`:

```ts
import fs from 'fs';
import path from 'path';
import os from 'os';
import {
  getSchedule,
  setSlot,
  resetSchedule,
} from '../src/lib/deliveryScheduleStore';

describe('deliveryScheduleStore', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kkc-schedule-'));
    process.env.KKC_SCHEDULE_DIR = tmpDir;
  });
  afterEach(() => {
    delete process.env.KKC_SCHEDULE_DIR;
    fs.rmSync(tmpDir, { recursive: true });
  });

  it('getSchedule returns all-empty when no file', () => {
    const s = getSchedule();
    expect(Object.keys(s.slots)).toHaveLength(10);
    expect(s.slots['monday_am'].jobs).toEqual([]);
    expect(s.slots['friday_pm'].jobs).toEqual([]);
  });

  it('setSlot saves a job and returns updated schedule', () => {
    const s = setSlot('monday_am', [{ jobNumber: '101', description: 'Smith Kitchen', address: '123 Main St' }]);
    expect(s.slots['monday_am'].jobs).toHaveLength(1);
    expect(s.slots['monday_am'].jobs[0].jobNumber).toBe('101');
    expect(s.slots['monday_am'].jobs[0].address).toBe('123 Main St');
  });

  it('setSlot persists and re-reads correctly', () => {
    setSlot('tuesday_pm', [{ jobNumber: '202', description: 'Jones Bath' }]);
    const s = getSchedule();
    expect(s.slots['tuesday_pm'].jobs[0].jobNumber).toBe('202');
  });

  it('setSlot clamps jobs to max 3', () => {
    const jobs = [
      { jobNumber: '1', description: 'A' },
      { jobNumber: '2', description: 'B' },
      { jobNumber: '3', description: 'C' },
      { jobNumber: '4', description: 'D' },
    ];
    const s = setSlot('wednesday_am', jobs);
    expect(s.slots['wednesday_am'].jobs).toHaveLength(3);
  });

  it('setSlot throws on invalid slot key', () => {
    expect(() => setSlot('saturday_am', [])).toThrow('Invalid slot key');
  });

  it('resetSchedule clears all slots', () => {
    setSlot('monday_am', [{ jobNumber: '1', description: 'A' }]);
    const s = resetSchedule();
    expect(s.slots['monday_am'].jobs).toEqual([]);
    expect(Object.keys(s.slots)).toHaveLength(10);
  });

  it('getSchedule returns empty when file is corrupt JSON', () => {
    const filePath = path.join(tmpDir, '.metadata', 'delivery_schedule.json');
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, 'NOT JSON');
    const s = getSchedule();
    expect(s.slots['monday_am'].jobs).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:\Scripts\kkc-admin\server
npx jest deliveryScheduleStore --no-coverage 2>&1 | head -20
```

Expected: `Cannot find module '../src/lib/deliveryScheduleStore'`

- [ ] **Step 3: Add types to `server/src/types.ts`**

Append these three interfaces after the last `export interface` at the bottom of `server/src/types.ts`:

```ts
export interface DeliveryJob {
  jobNumber: string;
  description: string;
  address?: string;
}

export interface DeliverySlot {
  jobs: DeliveryJob[];
}

export interface DeliverySchedule {
  schemaVersion: number;
  slots: Record<string, DeliverySlot>;
}
```

- [ ] **Step 4: Create `server/src/lib/deliveryScheduleStore.ts`**

```ts
import fs from 'fs';
import path from 'path';
import { getConfig } from '../config';
import { DeliveryJob, DeliverySchedule } from '../types';

// ── Valid slot keys ────────────────────────────────────────────────────────────
const DAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday'] as const;
const PERIODS = ['am', 'pm'] as const;
const VALID_SLOT_KEYS = new Set<string>(
  DAYS.flatMap(d => PERIODS.map(p => `${d}_${p}`))
);

function emptySchedule(): DeliverySchedule {
  const slots: DeliverySchedule['slots'] = {};
  for (const key of VALID_SLOT_KEYS) slots[key] = { jobs: [] };
  return { schemaVersion: 1, slots };
}

// ── Path ───────────────────────────────────────────────────────────────────────
// KKC_SCHEDULE_DIR env var lets tests override without touching the filesystem.
function scheduleDir(): string {
  return process.env.KKC_SCHEDULE_DIR ?? getConfig().basePath;
}
const schedulePath = () => path.join(scheduleDir(), '.metadata', 'delivery_schedule.json');

// ── Helpers ────────────────────────────────────────────────────────────────────
function readJson<T>(p: string, def: T): T {
  if (!fs.existsSync(p)) return def;
  try { return JSON.parse(fs.readFileSync(p, 'utf-8')); } catch { return def; }
}
function writeJson(p: string, data: unknown): void {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  const tmp = p + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, p);
}

// ── Public API ─────────────────────────────────────────────────────────────────
export function getSchedule(): DeliverySchedule {
  const raw = readJson<{ slots?: Record<string, { jobs?: DeliveryJob[] }> }>(
    schedulePath(), { slots: {} }
  );
  const base = emptySchedule();
  if (raw.slots && typeof raw.slots === 'object') {
    for (const key of VALID_SLOT_KEYS) {
      if (raw.slots[key]?.jobs && Array.isArray(raw.slots[key].jobs)) {
        base.slots[key] = { jobs: raw.slots[key].jobs as DeliveryJob[] };
      }
    }
  }
  return base;
}

export function setSlot(slotKey: string, jobs: DeliveryJob[]): DeliverySchedule {
  if (!VALID_SLOT_KEYS.has(slotKey)) throw new Error(`Invalid slot key: ${slotKey}`);
  const current = getSchedule();
  current.slots[slotKey] = { jobs: jobs.slice(0, 3) };
  writeJson(schedulePath(), current);
  return current;
}

export function resetSchedule(): DeliverySchedule {
  const s = emptySchedule();
  writeJson(schedulePath(), s);
  return s;
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd C:\Scripts\kkc-admin\server
npx jest deliveryScheduleStore --no-coverage
```

Expected: 7 tests pass, 0 failures.

- [ ] **Step 6: Build the server to confirm TypeScript is clean**

```bash
cd C:\Scripts\kkc-admin\server
npm run build 2>&1 | tail -5
```

Expected: No errors.

- [ ] **Step 7: Commit**

```bash
cd C:\Scripts\kkc-admin
git add server/src/types.ts server/src/lib/deliveryScheduleStore.ts server/__tests__/deliveryScheduleStore.test.ts
git commit -m "feat: add DeliverySchedule types and store

- DeliveryJob, DeliverySlot, DeliverySchedule interfaces
- deliveryScheduleStore: getSchedule/setSlot/resetSchedule
- Stores at {basePath}/.metadata/delivery_schedule.json
- Env var KKC_SCHEDULE_DIR for test overriding
- 7 tests passing"
```

---

### Task 2: Server route + registration

**Files:**
- Create: `C:\Scripts\kkc-admin\server\src\routes\deliverySchedule.ts`
- Modify: `C:\Scripts\kkc-admin\server\src\index.ts`

- [ ] **Step 1: Create `server/src/routes/deliverySchedule.ts`**

```ts
import { Router } from 'express';
import { getSchedule, setSlot, resetSchedule } from '../lib/deliveryScheduleStore';
import { DeliveryJob } from '../types';

const router = Router();

function errStatus(err: unknown): number {
  return String(err).includes('Invalid slot') ? 400 : 500;
}

// GET /api/delivery-schedule
router.get('/', (_req, res) => {
  try {
    const schedule = getSchedule();
    res.json({ schedule: schedule.slots });
  } catch (err) {
    res.status(errStatus(err)).json({ error: String(err) });
  }
});

// PUT /api/delivery-schedule
// Body: { slot: string, data: { jobs: DeliveryJob[] } }
router.put('/', (req, res) => {
  try {
    const { slot, data } = req.body as { slot?: string; data?: { jobs?: DeliveryJob[] } };
    if (!slot || typeof slot !== 'string') {
      return res.status(400).json({ error: 'slot is required' });
    }
    if (!data || !Array.isArray(data.jobs)) {
      return res.status(400).json({ error: 'data.jobs array is required' });
    }
    const updated = setSlot(slot, data.jobs);
    res.json({ schedule: updated.slots });
  } catch (err) {
    res.status(errStatus(err)).json({ error: String(err) });
  }
});

// POST /api/delivery-schedule/reset
router.post('/reset', (_req, res) => {
  try {
    const cleared = resetSchedule();
    res.json({ schedule: cleared.slots });
  } catch (err) {
    res.status(500).json({ error: String(err) });
  }
});

export default router;
```

- [ ] **Step 2: Register the router in `server/src/index.ts`**

Add this import after the existing router imports (after `import checklistRulesRouter`):

```ts
import deliveryScheduleRouter from './routes/deliverySchedule';
```

Add this line after `app.use('/api/checklist-rules', checklistRulesRouter);` and before `app.use('/api/jobs', jobsRouter);`:

```ts
app.use('/api/delivery-schedule', deliveryScheduleRouter);
```

The updated block should look like:

```ts
app.use('/api/checklist-rules', checklistRulesRouter);
app.use('/api/delivery-schedule', deliveryScheduleRouter);
app.use('/api/jobs', jobsRouter);
```

- [ ] **Step 3: Build the server**

```bash
cd C:\Scripts\kkc-admin\server
npm run build 2>&1 | tail -5
```

Expected: No errors. Output ends with something like `Found 0 errors.`

- [ ] **Step 4: Run the server and test the endpoints**

Start the server in the background:
```bash
cd C:\Scripts\kkc-admin\server
node dist/index.js &
```

Test GET:
```bash
curl -s http://localhost:4100/api/delivery-schedule | python -m json.tool | head -20
```
Expected: `{ "schedule": { "monday_am": { "jobs": [] }, ... } }` with all 10 slots.

Test PUT:
```bash
curl -s -X PUT http://localhost:4100/api/delivery-schedule \
  -H "Content-Type: application/json" \
  -d '{"slot":"monday_am","data":{"jobs":[{"jobNumber":"101","description":"Smith Kitchen","address":"123 Main St"}]}}' \
  | python -m json.tool
```
Expected: `{ "schedule": { "monday_am": { "jobs": [{ "jobNumber": "101", ... }] }, ... } }`

Test PUT with invalid slot:
```bash
curl -s -X PUT http://localhost:4100/api/delivery-schedule \
  -H "Content-Type: application/json" \
  -d '{"slot":"saturday_am","data":{"jobs":[]}}' \
  | python -m json.tool
```
Expected: `{ "error": "Invalid slot key: saturday_am" }` with HTTP 400.

Test reset:
```bash
curl -s -X POST http://localhost:4100/api/delivery-schedule/reset | python -m json.tool | head -5
```
Expected: `{ "schedule": { "monday_am": { "jobs": [] }, ... } }`

Kill the background server after testing.

- [ ] **Step 5: Commit**

```bash
cd C:\Scripts\kkc-admin
git add server/src/routes/deliverySchedule.ts server/src/index.ts
git commit -m "feat: add delivery schedule GET/PUT/POST routes

- GET /api/delivery-schedule returns all 10 slots
- PUT /api/delivery-schedule updates one slot (validates key, clamps to 3)
- POST /api/delivery-schedule/reset clears all slots
- Route registered in index.ts before /api/jobs"
```

---

### Task 3: kkc-admin client — types, API, view, App wiring

**Files:**
- Modify: `C:\Scripts\kkc-admin\client\src\types.ts`
- Modify: `C:\Scripts\kkc-admin\client\src\api.ts`
- Create: `C:\Scripts\kkc-admin\client\src\components\DeliveryScheduleView.tsx`
- Modify: `C:\Scripts\kkc-admin\client\src\App.tsx`

- [ ] **Step 1: Add client types to `client/src/types.ts`**

Append to the bottom of `client/src/types.ts`:

```ts
export interface DeliveryJob {
  jobNumber: string;
  description: string;
  address?: string;
}
export interface DeliverySlot {
  jobs: DeliveryJob[];
}
export type DeliverySlots = Record<string, DeliverySlot>;
```

- [ ] **Step 2: Add `deliverySchedule` to `client/src/api.ts`**

Find the closing `}` of the `api` export object in `api.ts`. Add this group before the final closing:

```ts
deliverySchedule: {
  get: (): Promise<{ schedule: import('./types').DeliverySlots }> =>
    fetch('/api/delivery-schedule').then(r => r.json()),
  updateSlot: (slot: string, jobs: import('./types').DeliveryJob[]): Promise<{ schedule: import('./types').DeliverySlots }> =>
    fetch('/api/delivery-schedule', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slot, data: { jobs } }),
    }).then(r => r.json()),
  reset: (): Promise<{ schedule: import('./types').DeliverySlots }> =>
    fetch('/api/delivery-schedule/reset', { method: 'POST' }).then(r => r.json()),
},
```

Note: if `api.ts` already imports from `./types`, replace the `import('./types').X` inline imports with the directly imported types.

- [ ] **Step 3: Create `client/src/components/DeliveryScheduleView.tsx`**

```tsx
import React, { useState, useEffect } from 'react';
import { api } from '../api';
import { DeliveryJob, DeliverySlots } from '../types';

const DAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday'] as const;
type Day = typeof DAYS[number];
const DAY_LABELS: Record<Day, string> = {
  monday: 'Mon', tuesday: 'Tue', wednesday: 'Wed', thursday: 'Thu', friday: 'Fri',
};
const PERIODS = ['am', 'pm'] as const;

function emptySlots(): DeliverySlots {
  const s: DeliverySlots = {};
  for (const d of DAYS) for (const p of PERIODS) s[`${d}_${p}`] = { jobs: [] };
  return s;
}

interface MoveSource { slotKey: string; idx: number }

export function DeliveryScheduleView({ onBack }: { onBack: () => void }) {
  const [slots, setSlots] = useState<DeliverySlots>(emptySlots());
  const [saving, setSaving] = useState<Record<string, boolean>>({});
  const [confirmReset, setConfirmReset] = useState(false);
  const [dragSrc, setDragSrc] = useState<MoveSource | null>(null);
  const [moveSrc, setMoveSrc] = useState<MoveSource | null>(null);

  useEffect(() => {
    api.deliverySchedule.get().then(r => { if (r.schedule) setSlots(r.schedule); });
  }, []);

  async function saveSlot(slotKey: string, jobs: DeliveryJob[]) {
    setSaving(s => ({ ...s, [slotKey]: true }));
    try {
      const r = await api.deliverySchedule.updateSlot(slotKey, jobs);
      if (r.schedule) setSlots(r.schedule);
    } finally {
      setSaving(s => ({ ...s, [slotKey]: false }));
    }
  }

  function updateJobField(slotKey: string, idx: number, patch: Partial<DeliveryJob>) {
    setSlots(prev => ({
      ...prev,
      [slotKey]: { jobs: (prev[slotKey]?.jobs ?? []).map((j, i) => i === idx ? { ...j, ...patch } : j) },
    }));
  }

  function addJob(slotKey: string) {
    const jobs = slots[slotKey]?.jobs ?? [];
    if (jobs.length >= 3) return;
    const next = [...jobs, { jobNumber: '', description: '', address: '' }];
    setSlots(prev => ({ ...prev, [slotKey]: { jobs: next } }));
    saveSlot(slotKey, next);
  }

  function removeJob(slotKey: string, idx: number) {
    const jobs = (slots[slotKey]?.jobs ?? []).filter((_, i) => i !== idx);
    saveSlot(slotKey, jobs);
  }

  // Drag-to-reorder (HTML5 drag events)
  function onDragStart(slotKey: string, idx: number) { setDragSrc({ slotKey, idx }); }
  function onDrop(destKey: string) {
    if (!dragSrc) return;
    const srcJobs = [...(slots[dragSrc.slotKey]?.jobs ?? [])];
    const [job] = srcJobs.splice(dragSrc.idx, 1);
    const destJobs = [...(slots[destKey]?.jobs ?? [])];
    if (destJobs.length >= 3) { setDragSrc(null); return; }
    destJobs.push(job);
    const next = { ...slots, [dragSrc.slotKey]: { jobs: srcJobs }, [destKey]: { jobs: destJobs } };
    setSlots(next);
    saveSlot(dragSrc.slotKey, srcJobs);
    saveSlot(destKey, destJobs);
    setDragSrc(null);
  }

  // Touch move mode: select a job, then tap a destination slot
  function selectOrMove(slotKey: string, idx: number) {
    if (!moveSrc) {
      setMoveSrc({ slotKey, idx });
      return;
    }
    if (moveSrc.slotKey === slotKey && moveSrc.idx === idx) {
      setMoveSrc(null);
      return;
    }
    const srcJobs = [...(slots[moveSrc.slotKey]?.jobs ?? [])];
    const [job] = srcJobs.splice(moveSrc.idx, 1);
    const destJobs = [...(slots[slotKey]?.jobs ?? [])];
    if (destJobs.length >= 3) { setMoveSrc(null); return; }
    destJobs.push(job);
    const next = { ...slots, [moveSrc.slotKey]: { jobs: srcJobs }, [slotKey]: { jobs: destJobs } };
    setSlots(next);
    saveSlot(moveSrc.slotKey, srcJobs);
    saveSlot(slotKey, destJobs);
    setMoveSrc(null);
  }

  async function handleReset() {
    const r = await api.deliverySchedule.reset();
    if (r.schedule) setSlots(r.schedule);
    setConfirmReset(false);
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="bg-white dark:bg-kkc-surface border-b border-gray-200 dark:border-kkc-border px-4 py-2.5 flex items-center gap-3 flex-shrink-0">
        <button
          onClick={onBack}
          className="text-sm text-gray-400 hover:text-kkc-primary font-mono transition-colors"
        >
          ← Back
        </button>
        <h2 className="font-bold text-sm font-mono text-kkc-primary">Delivery Schedule</h2>
        <div className="ml-auto flex items-center gap-2">
          {confirmReset ? (
            <>
              <span className="text-xs text-red-400 font-mono">Clear all slots?</span>
              <button
                onClick={handleReset}
                className="text-xs bg-red-500 text-white px-2 py-1 rounded hover:bg-red-600 transition-colors"
              >
                Yes, Reset
              </button>
              <button
                onClick={() => setConfirmReset(false)}
                className="text-xs border border-gray-300 dark:border-kkc-border px-2 py-1 rounded hover:border-gray-400 transition-colors"
              >
                Cancel
              </button>
            </>
          ) : (
            <button
              onClick={() => setConfirmReset(true)}
              className="text-xs text-red-400 hover:text-red-500 border border-red-200 dark:border-red-900 px-2 py-1 rounded transition-colors"
            >
              Reset All
            </button>
          )}
        </div>
      </div>

      {/* Grid */}
      <div className="flex-1 overflow-auto p-4">
        <div className="grid gap-3 min-w-[600px]" style={{ gridTemplateColumns: 'repeat(5, minmax(0, 1fr))' }}>
          {DAYS.map(day => (
            <div key={day}>
              <div className="text-xs font-bold text-center text-gray-500 dark:text-kkc-muted uppercase mb-2 font-mono tracking-wider">
                {DAY_LABELS[day]}
              </div>
              {PERIODS.map(period => {
                const key = `${day}_${period}`;
                const jobs = slots[key]?.jobs ?? [];
                const isDropTarget = dragSrc && dragSrc.slotKey !== key;
                return (
                  <div
                    key={key}
                    className={`border rounded p-2 mb-3 min-h-[90px] transition-colors ${
                      isDropTarget
                        ? 'border-kkc-primary bg-kkc-primary/5'
                        : 'border-gray-200 dark:border-kkc-border bg-white dark:bg-kkc-surface'
                    }`}
                    onDragOver={e => e.preventDefault()}
                    onDrop={() => onDrop(key)}
                  >
                    <div className="text-[9px] font-mono font-bold text-gray-400 dark:text-kkc-muted uppercase mb-1.5 tracking-widest">
                      {period}
                    </div>
                    {jobs.map((job, idx) => {
                      const isMoveSrc = moveSrc?.slotKey === key && moveSrc?.idx === idx;
                      return (
                        <div
                          key={idx}
                          draggable
                          onDragStart={() => onDragStart(key, idx)}
                          onDragEnd={() => setDragSrc(null)}
                          className={`mb-1.5 p-1.5 rounded border text-[10px] bg-gray-50 dark:bg-kkc-bg cursor-grab active:cursor-grabbing transition-colors ${
                            isMoveSrc ? 'border-kkc-primary ring-1 ring-kkc-primary' : 'border-gray-200 dark:border-kkc-border'
                          }`}
                        >
                          <div className="flex gap-1 items-center mb-1">
                            <input
                              value={job.jobNumber}
                              onChange={e => updateJobField(key, idx, { jobNumber: e.target.value })}
                              onBlur={() => saveSlot(key, slots[key]?.jobs ?? [])}
                              placeholder="#"
                              className="w-10 bg-transparent border-b border-gray-300 dark:border-kkc-border text-[10px] font-mono outline-none focus:border-kkc-primary placeholder-gray-300"
                            />
                            <input
                              value={job.description}
                              onChange={e => updateJobField(key, idx, { description: e.target.value })}
                              onBlur={() => saveSlot(key, slots[key]?.jobs ?? [])}
                              placeholder="Name"
                              className="flex-1 bg-transparent border-b border-gray-300 dark:border-kkc-border text-[10px] outline-none focus:border-kkc-primary placeholder-gray-300 min-w-0"
                            />
                            <button
                              onClick={() => removeJob(key, idx)}
                              className="text-red-400 hover:text-red-500 font-bold text-[11px] flex-shrink-0 w-4 text-center leading-none"
                              title="Remove job"
                            >
                              ×
                            </button>
                          </div>
                          <input
                            value={job.address ?? ''}
                            onChange={e => updateJobField(key, idx, { address: e.target.value })}
                            onBlur={() => saveSlot(key, slots[key]?.jobs ?? [])}
                            placeholder="Address (optional)"
                            className="w-full bg-transparent border-b border-gray-300 dark:border-kkc-border text-[10px] outline-none focus:border-kkc-primary placeholder-gray-300"
                          />
                          <div className="flex justify-end mt-0.5">
                            <button
                              onClick={() => selectOrMove(key, idx)}
                              className={`text-[9px] font-mono transition-colors ${
                                isMoveSrc ? 'text-kkc-primary font-bold' : 'text-gray-400 hover:text-kkc-primary'
                              }`}
                              title={isMoveSrc ? 'Tap another slot to move here' : 'Move to another slot'}
                            >
                              {isMoveSrc ? '↕ tap destination' : '↕ move'}
                            </button>
                          </div>
                        </div>
                      );
                    })}
                    {jobs.length < 3 && (
                      <button
                        onClick={() => addJob(key)}
                        className="w-full text-[9px] text-gray-400 hover:text-kkc-primary border border-dashed border-gray-200 dark:border-kkc-border rounded py-1 mt-0.5 transition-colors font-mono"
                      >
                        + add
                      </button>
                    )}
                    {saving[key] && (
                      <div className="text-[8px] text-gray-300 dark:text-kkc-muted text-right mt-0.5 font-mono">saving…</div>
                    )}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Wire `DeliveryScheduleView` into `client/src/App.tsx`**

Add the import near the top of `App.tsx`:
```tsx
import { DeliveryScheduleView } from './components/DeliveryScheduleView';
```

Add the state inside the `App` function body (after the existing state declarations):
```tsx
const [showSchedule, setShowSchedule] = useState(false);
```

Replace the entire `<main>` element content with the following (the outer `<main>` tag stays the same — replace the conditional content inside it):

```tsx
<main className="flex-1 flex flex-col overflow-hidden">
  {showSchedule ? (
    <DeliveryScheduleView onBack={() => setShowSchedule(false)} />
  ) : job ? (
    <>
      <div className="bg-white dark:bg-kkc-surface border-b border-gray-200 dark:border-kkc-border px-4 py-2.5 flex items-center gap-3">
        <div>
          <div className="font-bold text-sm">
            <span className="font-mono text-kkc-primary">#{job.jobNumber}</span>
            <span className="text-gray-700 dark:text-gray-200"> — {job.jobName}</span>
          </div>
          <div className="text-gray-400 dark:text-gray-500 text-[10px] font-mono">{job.folderName}</div>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={() => setShowSchedule(true)}
            className="text-[10px] font-mono text-gray-400 hover:text-kkc-primary border border-gray-200 dark:border-kkc-border rounded px-2 py-1 transition-colors"
          >
            📋 Schedule
          </button>
          <button
            onClick={() => { refreshChecklist(); runScan(job.folderName); }}
            title="Re-scan rules for this job"
            className="text-[10px] font-mono text-gray-400 hover:text-kkc-primary border border-gray-200 dark:border-kkc-border rounded px-2 py-1 transition-colors"
          >
            🔍 Rescan
          </button>
          <button
            onClick={toggleTheme}
            title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
            className="text-lg text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 px-2"
          >
            {isDark ? '☀' : '🌙'}
          </button>
        </div>
      </div>
      <ModeTabs
        active={activeTab}
        onChange={setActiveTab}
        pendingSpecialty={pendingSpecialty}
        pendingRules={pendingRules.length}
      />
      <div className="flex-1 overflow-y-auto p-4">
        {activeTab === 'CNC' && job && (
          <CncTab
            job={job}
            folderName={job.folderName}
            onOpenViewer={openViewer}
            specialtyItems={specialtyItems}
            onSpecialtyChanged={refreshSpecialty}
          />
        )}
        {activeTab === 'HARDWOODS' && job && (
          <HardwoodsTab
            job={job}
            ripItems={ripItems}
            folderName={job.folderName}
            onRipItemAdded={refreshRipItems}
            specialtyItems={specialtyItems}
            onSpecialtyChanged={refreshSpecialty}
          />
        )}
        {activeTab === 'ASSEMBLY' && job && (
          <AssemblyTab
            job={job}
            folderName={job.folderName}
            specialtyItems={specialtyItems}
            onSpecialtyChanged={refreshSpecialty}
          />
        )}
        {activeTab === 'CUSTOM_SPECIALTY' && job && (
          <CustomSpecialtyTab
            folderName={job.folderName}
            specialtyItems={specialtyItems}
            onSpecialtyChanged={refreshSpecialty}
            checklistItems={checklist}
            onChecklistChanged={refreshChecklist}
            pendingRules={pendingRules}
            onScanRefresh={() => { refreshChecklist(); runScan(job.folderName); }}
          />
        )}
        {activeTab === 'BOARD_STOCK' && job && (
          <BoardStockTab
            folderName={job.folderName}
            items={boardStockItems}
            onChanged={refreshBoardStock}
          />
        )}
      </div>
    </>
  ) : (
    <div className="flex flex-col items-center justify-center h-full gap-4">
      <button
        onClick={() => setShowSchedule(true)}
        className="text-sm font-mono text-kkc-primary hover:underline border border-kkc-primary/30 rounded px-3 py-1.5"
      >
        📋 Delivery Schedule
      </button>
      <div className="text-gray-400 dark:text-kkc-muted text-sm font-mono tracking-wide uppercase">Select a job</div>
      <button onClick={toggleTheme} className="text-xl text-gray-300 dark:text-gray-600 hover:text-gray-500">
        {isDark ? '☀' : '🌙'}
      </button>
    </div>
  )}
</main>
```

- [ ] **Step 5: Build the client**

```bash
cd C:\Scripts\kkc-admin\client
npm run build 2>&1 | tail -10
```

Expected: No TypeScript errors, build succeeds.

- [ ] **Step 6: Manual test in browser**

Start the full server: `cd C:\Scripts\kkc-admin\server && node dist/index.js`
Open `http://localhost:4100` in a browser.

Verify:
1. When no job is selected, "📋 Delivery Schedule" button is visible
2. Clicking it shows the 5-column Mon–Fri grid with AM/PM sub-rows
3. Adding a job in any slot (type number + name + address), blur → "saving…" appears briefly, then slot shows the data
4. Clicking "× " removes the job
5. "Reset All" → "Yes, Reset" clears all slots
6. "← Back" returns to job selection

- [ ] **Step 7: Commit**

```bash
cd C:\Scripts\kkc-admin
git add client/src/types.ts client/src/api.ts client/src/components/DeliveryScheduleView.tsx client/src/App.tsx
git commit -m "feat: add delivery schedule admin UI

- DeliveryJob/DeliverySlot/DeliverySlots types in client/src/types.ts
- deliverySchedule API group in api.ts
- DeliveryScheduleView: 5-day × AM/PM grid with inline editing,
  add/remove jobs (max 3 per slot), drag-to-reorder, touch move mode,
  Reset All with confirmation, auto-save on blur
- App.tsx: Schedule button in job header and no-job placeholder,
  showSchedule state replaces job content area with DeliveryScheduleView"
```

---

### Task 4: Android models

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/models/DeliveryScheduleModels.kt`

All paths below are relative to `C:\Scripts\KKCSheetTracker`.

- [ ] **Step 1: Create `data/models/DeliveryScheduleModels.kt`**

```kotlin
package com.kkc.sheettracker.data.models

data class DeliveryJob(
    val jobNumber: String = "",
    val description: String = "",
    val address: String = ""
)

data class DeliverySlot(
    val jobs: List<DeliveryJob> = emptyList()
)

data class DeliverySchedule(
    val slots: Map<String, DeliverySlot> = emptyMap()
) {
    fun slot(day: String, period: String): DeliverySlot =
        slots["${day}_${period}"] ?: DeliverySlot()

    val isEmpty: Boolean
        get() = slots.values.all { it.jobs.isEmpty() }
}

val DELIVERY_DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday")
val DELIVERY_DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
val DELIVERY_PERIODS = listOf("am", "pm")
```

- [ ] **Step 2: Build to verify the file compiles**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` (or at minimum, no errors in `DeliveryScheduleModels.kt`)

- [ ] **Step 3: Commit**

```bash
cd C:\Scripts\KKCSheetTracker
git add app/src/main/java/com/kkc/sheettracker/data/models/DeliveryScheduleModels.kt
git commit -m "feat: add DeliverySchedule Kotlin models

- DeliveryJob, DeliverySlot, DeliverySchedule data classes
- DeliverySchedule.slot(day, period) helper
- DeliverySchedule.isEmpty computed property
- DELIVERY_DAYS, DELIVERY_DAY_LABELS, DELIVERY_PERIODS constants"
```

---

### Task 5: Android repository

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt`

- [ ] **Step 1: Create `data/DeliveryScheduleRepository.kt`**

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import java.io.File

/**
 * Reads the delivery schedule from the shared network drive.
 * Storage path: {baseDir}/.metadata/delivery_schedule.json
 * Written by kkc-admin; read-only on the tablet.
 * Call on Dispatchers.IO.
 */
class DeliveryScheduleRepository(private val baseDir: File) {

    private val gson = GsonBuilder().create()

    fun fetchSchedule(): DeliverySchedule {
        val file = File(baseDir, ".metadata/delivery_schedule.json")
        if (!file.exists() || !file.isFile) return DeliverySchedule()
        return runCatching { parseSchedule(file.readText()) }.getOrElse { DeliverySchedule() }
    }

    private fun parseSchedule(json: String): DeliverySchedule {
        val root = gson.fromJson(json, JsonObject::class.java) ?: return DeliverySchedule()
        val slotsObj = root.getAsJsonObject("slots") ?: return DeliverySchedule()
        val slots = mutableMapOf<String, DeliverySlot>()

        for (day in DELIVERY_DAYS) {
            for (period in DELIVERY_PERIODS) {
                val key = "${day}_${period}"
                val slotObj = slotsObj.getAsJsonObject(key)
                val jobs = mutableListOf<DeliveryJob>()
                if (slotObj != null) {
                    val jobsArr = slotObj.getAsJsonArray("jobs")
                    jobsArr?.forEach { elem ->
                        val obj = elem.asJsonObject
                        jobs.add(
                            DeliveryJob(
                                jobNumber = obj.get("jobNumber")?.asString ?: "",
                                description = obj.get("description")?.asString ?: "",
                                address = obj.get("address")?.asString ?: ""
                            )
                        )
                    }
                }
                slots[key] = DeliverySlot(jobs = jobs)
            }
        }
        return DeliverySchedule(slots = slots)
    }
}
```

- [ ] **Step 2: Build to verify the file compiles**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd C:\Scripts\KKCSheetTracker
git add app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt
git commit -m "feat: add DeliveryScheduleRepository

- Reads {baseDir}/.metadata/delivery_schedule.json
- Parses slots/jobs via Gson JsonObject manually (no reflection)
- Returns empty DeliverySchedule on missing file or any parse error
- Call on Dispatchers.IO"
```

---

### Task 6: Android widget

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidget.kt`

- [ ] **Step 1: Create `ui/components/DeliveryScheduleWidget.kt`**

```kotlin
package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DELIVERY_DAY_LABELS
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS

/**
 * Compact always-visible delivery schedule row, shown above the job board grid.
 * Hidden when the schedule is empty. Tap to expand to full-screen dialog.
 */
@Composable
fun DeliveryScheduleWidget(
    schedule: DeliverySchedule,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (schedule.isEmpty) return

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onTap() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "DELIVERIES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DELIVERY_DAYS.forEachIndexed { dayIdx, day ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = DELIVERY_DAY_LABELS[dayIdx],
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        DELIVERY_PERIODS.forEach { period ->
                            val slot = schedule.slot(day, period)
                            Text(
                                text = period.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            if (slot.jobs.isEmpty()) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            } else {
                                slot.jobs.forEach { job ->
                                    Text(
                                        text = "${job.jobNumber} — ${job.description}",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify the file compiles**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd C:\Scripts\KKCSheetTracker
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidget.kt
git commit -m "feat: add DeliveryScheduleWidget composable

- Compact surface widget above job board grid
- Shows Mon–Fri columns with AM/PM sub-rows and job number/name
- Hidden when schedule.isEmpty
- Calls onTap to open full-screen dialog"
```

---

### Task 7: Android dialog

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt`

- [ ] **Step 1: Create `ui/components/DeliveryScheduleDialog.kt`**

```kotlin
package com.kkc.sheettracker.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DELIVERY_DAY_LABELS
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import java.net.URLEncoder

/**
 * Full-screen dialog showing the weekly delivery schedule with addresses,
 * map links, and copy-to-clipboard for each job.
 * Read-only — editing is done in kkc-admin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryScheduleDialog(
    schedule: DeliverySchedule,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("This Week's Delivery Schedule") },
                    actions = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                DELIVERY_DAYS.forEachIndexed { dayIdx, day ->
                    item {
                        Text(
                            text = DELIVERY_DAY_LABELS[dayIdx],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        DELIVERY_PERIODS.forEach { period ->
                            val slot = schedule.slot(day, period)
                            Text(
                                text = period.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            if (slot.jobs.isEmpty()) {
                                Text(
                                    text = "No deliveries",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            } else {
                                slot.jobs.forEach { job ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Text(
                                                text = job.jobNumber,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = job.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (job.address.isNotBlank()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                ) {
                                                    Text(
                                                        text = job.address,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            val encoded = URLEncoder.encode(job.address, "UTF-8")
                                                            val intent = Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse("geo:0,0?q=$encoded")
                                                            )
                                                            context.startActivity(intent)
                                                        }
                                                    ) {
                                                        Icon(
                                                            Icons.Default.LocationOn,
                                                            contentDescription = "Open in Maps",
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                            clipboard.setPrimaryClip(ClipData.newPlainText("address", job.address))
                                                        }
                                                    ) {
                                                        Icon(
                                                            Icons.Default.ContentCopy,
                                                            contentDescription = "Copy address",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify the file compiles**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd C:\Scripts\KKCSheetTracker
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt
git commit -m "feat: add DeliveryScheduleDialog composable

- Full-screen Dialog (usePlatformDefaultWidth=false)
- Scaffold with TopAppBar title + close IconButton
- LazyColumn: day headers → AM/PM sections → job Cards
- Each job: number (bold) + description + address row
- Address row: geo: map intent + clipboard copy
- Empty slot: italic 'No deliveries' in muted color"
```

---

### Task 8: Android wiring — JobBrowserScreen + NavGraph

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

- [ ] **Step 1: Update `JobBrowserScreen` signature and imports**

Add these imports to the existing import block in `JobBrowserScreen.kt`:

```kotlin
import androidx.activity.compose.BackHandler
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog
import kotlinx.coroutines.withContext
import java.io.File
```

Add `deliveryScheduleRepository: DeliveryScheduleRepository` as a new parameter to the `JobBrowserScreen` composable function signature, after `progressStore`:

```kotlin
@Composable
fun JobBrowserScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    hardwoodsRepository: HardwoodsRepository,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    deliveryScheduleRepository: DeliveryScheduleRepository,   // ← new
    appStateFlags: AppStateFeatureFlags,
    onJobClick: (Job) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (Job) -> Unit,
    onView3D: (Job) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
)
```

- [ ] **Step 2: Add schedule state inside `JobBrowserScreen`**

Add these two `remember` / `var` declarations inside the function body, right after `val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }`:

```kotlin
val deliverySchedule = remember(scanState.snapshot.basePath, scanState.snapshot.generation) {
    deliveryScheduleRepository.fetchSchedule()
}
var showScheduleDialog by remember { mutableStateOf(false) }
```

Also add this import if not already present (it won't be, since it's new):
```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
```
(Both are likely already imported — skip if they are.)

- [ ] **Step 3: Insert the widget above `JobBoardGrid`**

Inside the `AnimatedContent` block, inside the `isBoardView` branch, add `DeliveryScheduleWidget` directly above the `JobBoardGrid` call. Find this block:

```kotlin
} else if (isBoardView) {
    JobBoardGrid(
        items = filteredJobs.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName) },
        ...
    )
}
```

Replace it with:

```kotlin
} else if (isBoardView) {
    Column(modifier = Modifier.fillMaxSize()) {
        DeliveryScheduleWidget(
            schedule = deliverySchedule,
            onTap = { showScheduleDialog = true },
            modifier = Modifier.fillMaxWidth()
        )
        JobBoardGrid(
            items = filteredJobs.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName) },
            jobRepository = jobRepository,
            onItemClick = { boardItem ->
                filteredJobs.find { it.folderName == boardItem.folderName }
                    ?.let { onJobClick(it) }
            },
            modifier = Modifier.weight(1f),
            scanGeneration = scanState.snapshot.generation
        )
    }
}
```

Make sure `Column` is already imported; if not, add `import androidx.compose.foundation.layout.Column`.

- [ ] **Step 4: Add the dialog + BackHandler after the Scaffold block**

After the closing `}` of the main `Scaffold` composable (and before the `selectedHistoryJob` block at the bottom), add:

```kotlin
if (showScheduleDialog) {
    DeliveryScheduleDialog(
        schedule = deliverySchedule,
        onDismiss = { showScheduleDialog = false }
    )
}

BackHandler(enabled = showScheduleDialog) {
    showScheduleDialog = false
}
```

- [ ] **Step 5: Update `NavGraph.kt` to construct and pass the repository**

In `NavGraph.kt`, find the function `MultiBackStackNavigation` signature. Add `deliveryScheduleRepository` parameter after `jobRepository`:

Actually, the cleaner approach is to construct the repository *inside* `MultiBackStackNavigation` and `LegacySingleStackNavigation` using `remember(basePath)` — the same pattern used for `HardwoodsProgressStore` and `SpecialtyProgressStore`:

In `MultiBackStackNavigation`, add this `remember` block near the top (after `sharedSpecialtyProgressStore`):

```kotlin
val deliveryScheduleRepository = remember(basePath) {
    DeliveryScheduleRepository(File(basePath))
}
```

And add the import at the top of `NavGraph.kt`:

```kotlin
import com.kkc.sheettracker.data.DeliveryScheduleRepository
```

Then find every `JobBrowserScreen(` call site within `MultiBackStackNavigation` and add:
```kotlin
deliveryScheduleRepository = deliveryScheduleRepository,
```

Do the same inside `LegacySingleStackNavigation` — add the `remember` block and update all `JobBrowserScreen(` call sites there too.

Full context of the change for `MultiBackStackNavigation` (the `remember` block goes after line ~146 in NavGraph.kt):

```kotlin
val deliveryScheduleRepository = remember(basePath) {
    DeliveryScheduleRepository(File(basePath))
}
```

Full context of one `JobBrowserScreen` call site with the new parameter:

```kotlin
JobBrowserScreen(
    scanCoordinator = scanCoordinator,
    appStateStore = appStateStore,
    hardwoodsRepository = hardwoodsRepository,
    jobRepository = jobRepository,
    progressStore = progressStore,
    deliveryScheduleRepository = deliveryScheduleRepository,   // ← new
    appStateFlags = appStateFlags,
    onJobClick = { job ->
        navController.navigate("job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
            launchSingleTop = true
        }
    },
    // ... rest unchanged ...
)
```

- [ ] **Step 6: Build the full Android project**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

If there are compilation errors, read the error output carefully:
- "Unresolved reference: DeliveryScheduleRepository" → check imports in NavGraph.kt
- "Unresolved reference: DeliveryScheduleWidget" → check imports in JobBrowserScreen.kt
- "None of the following candidates is applicable" on `JobBrowserScreen` → a call site in NavGraph.kt is missing the new `deliveryScheduleRepository` parameter

- [ ] **Step 7: Commit**

```bash
cd C:\Scripts\KKCSheetTracker
git add app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt \
        app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire delivery schedule into job board

- JobBrowserScreen: new deliveryScheduleRepository param
- Load schedule with remember(basePath, generation)
- DeliveryScheduleWidget above JobBoardGrid in board view
- DeliveryScheduleDialog + BackHandler on showScheduleDialog
- NavGraph: construct DeliveryScheduleRepository(File(basePath))
  in Multi/LegacySingleStack navigation, pass to JobBrowserScreen"
```

---

## Verification

After all tasks are complete, run through these scenarios manually:

1. **Admin — add jobs to a slot**: Open kkc-admin → click "📋 Schedule" → Monday AM → add a job (number + name + address) → blur field → confirm the grid shows "saving…" then clears. Check `Y:\Ready Jobs\.metadata\delivery_schedule.json` contains the new entry.

2. **Admin — drag between slots**: Drag a job card from Monday AM to Tuesday PM. Confirm job moves, both slots save immediately.

3. **Admin — touch move**: Click "↕ move" on a job → it highlights and shows "↕ tap destination". Click "↕ move" on a job in another slot → job moves.

4. **Admin — reset**: Click "Reset All" → "Yes, Reset" → all slots clear. JSON file reflects empty slots.

5. **Android widget visible**: With at least one job in any slot, switch to board view → `DeliveryScheduleWidget` appears above the job grid, showing correct day/period and job number – name.

6. **Android widget hidden**: Clear all slots in admin → on next scan refresh, widget disappears from board view.

7. **Android dialog — expand**: Tap the widget → `DeliveryScheduleDialog` opens full screen showing all days.

8. **Android dialog — map**: Tap the map pin on a job with an address → Google Maps opens with the address.

9. **Android dialog — copy**: Tap copy icon → address copied to clipboard.

10. **Android dialog — close**: Tap X or press back → dialog closes, returns to board.

11. **Live update**: Add a job in admin → on next Android scan refresh, widget updates automatically.

12. **Missing file**: Delete `delivery_schedule.json` → Android loads normally, widget is hidden, no crash.
