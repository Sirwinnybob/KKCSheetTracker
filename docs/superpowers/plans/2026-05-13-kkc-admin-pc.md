# KKC Admin PC Dashboard — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a TypeScript web app (Express + React) at `C:\Scripts\kkc-admin` that serves as a PC admin dashboard for KKCSheetTracker — showing job progress across all modes and enabling admin actions (custom rip items, checklists).

**Architecture:** Node.js/Express backend reads the same file-based tracker data the Android tablets use and exposes a REST API. React/Vite frontend consumes that API. Single `npm start` boots everything; built client is served as static files from Express.

**Tech Stack:** Node.js 20+, TypeScript 5, Express 4, React 18, Vite 5, Tailwind CSS 3, PDF.js (pdfjs-dist), Jest + ts-jest (server tests), uuid

---

## File Map

```
C:\Scripts\kkc-admin\
  package.json                        ← root workspace (workspaces: [server, client])
  config.json                         ← { basePath, port } — gitignored
  config.example.json                 ← checked-in example
  .gitignore

  server/
    package.json
    tsconfig.json
    jest.config.ts
    src/
      index.ts                        ← Express entry: mounts routes, serves client/dist
      config.ts                       ← loads + validates config.json → Config
      deviceId.ts                     ← exports deviceId = "admin-" + os.hostname()
      types.ts                        ← ALL shared server types (TrackerAction, JobSummary, etc.)
      lib/
        jobScanner.ts                 ← scans basePath, reads deployment_gate.json per folder
        progressReader.ts             ← merges CNC/.tracker/*.json → ModeProgress
        hardwoodsReader.ts            ← reads cutlist_index.json + hardwoods tracker → ModeProgress
        metadataReader.ts             ← reads CNC/.metadata/<pdf>.json → PdfMetadata (parts + OCR boxes)
        adminStore.ts                 ← R/W .metadata/admin/rip_items.json + checklist.json
        trackerWriter.ts              ← appends TrackerAction to CNC/.tracker/admin-{hostname}.json
      routes/
        jobs.ts                       ← GET /api/jobs, GET /api/jobs/:folder
        pdf.ts                        ← GET /api/pdf/:folder/:filename (stream)
        tracker.ts                    ← POST /api/jobs/:folder/tracker
        admin.ts                      ← CRUD /api/jobs/:folder/rip-items + /checklist
    __tests__/
      jobScanner.test.ts
      progressReader.test.ts
      adminStore.test.ts
      trackerWriter.test.ts

  client/
    package.json
    tsconfig.json
    vite.config.ts
    index.html
    src/
      main.tsx
      App.tsx
      types.ts                        ← mirrors server types used by client
      api.ts                          ← typed fetch() wrappers for every endpoint
      hooks/
        useJobs.ts                    ← polls GET /api/jobs every 30s
        useJob.ts                     ← fetches single job detail
        useChecklist.ts               ← fetches + mutates checklist
        useRipItems.ts                ← fetches + mutates rip items
      components/
        Sidebar.tsx                   ← job list + search + 3-mode mini bars
        JobCard.tsx                   ← single job row in sidebar
        MiniProgressBars.tsx          ← 3 tiny labeled bars (CNC/HW/ASM)
        ModeTabs.tsx                  ← tab bar: CNC | Hardwoods | Assembly | ☑
        MaterialCard.tsx              ← card with left border + page strip
        PageStrip.tsx                 ← row of colored page chips
        ProgressBar.tsx               ← labeled bar + stat counts (done/bad/skip/left)
        StatusChip.tsx                ← green/red/amber/gray chip
        CncTab.tsx                    ← overall bar + material cards
        HardwoodsTab.tsx              ← rip summary + custom items list
        AssemblyTab.tsx               ← assembly material cards
        ChecklistTab.tsx              ← checklist list + check off
        AddRipItemDialog.tsx          ← modal form
        AddChecklistItemDialog.tsx    ← modal form
      viewer/
        SheetViewer.tsx               ← split layout shell
        PdfCanvas.tsx                 ← PDF.js page renderer (canvas)
        PartOverlay.tsx               ← SVG overlay for part boxes
        PartTable.tsx                 ← right panel: parts list + status
        PageThumbnailStrip.tsx        ← bottom chip strip
```

---

## Task 1: Project Scaffold

**Files:**
- Create: `C:\Scripts\kkc-admin\package.json`
- Create: `C:\Scripts\kkc-admin\.gitignore`
- Create: `C:\Scripts\kkc-admin\config.example.json`
- Create: `C:\Scripts\kkc-admin\server\package.json`
- Create: `C:\Scripts\kkc-admin\server\tsconfig.json`
- Create: `C:\Scripts\kkc-admin\server\jest.config.ts`
- Create: `C:\Scripts\kkc-admin\client\package.json`
- Create: `C:\Scripts\kkc-admin\client\tsconfig.json`
- Create: `C:\Scripts\kkc-admin\client\vite.config.ts`
- Create: `C:\Scripts\kkc-admin\client\index.html`

- [ ] **Step 1: Create root workspace**

```bash
mkdir "C:\Scripts\kkc-admin" && cd "C:\Scripts\kkc-admin" && git init
```

`package.json`:
```json
{
  "name": "kkc-admin",
  "private": true,
  "workspaces": ["server", "client"],
  "scripts": {
    "dev": "concurrently \"npm run dev -w server\" \"npm run dev -w client\"",
    "build": "npm run build -w client && npm run build -w server",
    "start": "npm run start -w server",
    "test": "npm run test -w server"
  },
  "devDependencies": { "concurrently": "^8.2.2" }
}
```

- [ ] **Step 2: Create .gitignore and config files**

`.gitignore`:
```
node_modules/
dist/
server/public/
config.json
```

`config.example.json`:
```json
{ "basePath": "\\\\server\\JobFiles", "port": 3000 }
```

Copy to `config.json` and set real `basePath`.

- [ ] **Step 3: Scaffold server package**

`server/package.json`:
```json
{
  "name": "kkc-admin-server",
  "version": "1.0.0",
  "scripts": {
    "dev": "ts-node-dev --respawn src/index.ts",
    "build": "tsc",
    "start": "node dist/index.js",
    "test": "jest"
  },
  "dependencies": { "express": "^4.18.2", "uuid": "^9.0.0" },
  "devDependencies": {
    "@types/express": "^4.17.21",
    "@types/node": "^20.11.0",
    "@types/uuid": "^9.0.7",
    "ts-jest": "^29.1.2",
    "ts-node-dev": "^2.0.0",
    "typescript": "^5.3.3",
    "jest": "^29.7.0",
    "@types/jest": "^29.5.11",
    "supertest": "^6.3.4",
    "@types/supertest": "^6.0.2"
  }
}
```

`server/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020", "module": "commonjs", "lib": ["ES2020"],
    "outDir": "./dist", "rootDir": "./src",
    "strict": true, "esModuleInterop": true,
    "resolveJsonModule": true, "skipLibCheck": true
  },
  "include": ["src"],
  "exclude": ["node_modules", "dist", "__tests__"]
}
```

`server/jest.config.ts`:
```typescript
export default {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['**/__tests__/**/*.test.ts'],
};
```

- [ ] **Step 4: Scaffold client package**

`client/package.json`:
```json
{
  "name": "kkc-admin-client",
  "version": "1.0.0",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build --outDir ../server/public",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.2.0", "react-dom": "^18.2.0", "pdfjs-dist": "^4.0.379"
  },
  "devDependencies": {
    "@types/react": "^18.2.48", "@types/react-dom": "^18.2.18",
    "@vitejs/plugin-react": "^4.2.1",
    "autoprefixer": "^10.4.17", "postcss": "^8.4.33",
    "tailwindcss": "^3.4.1", "typescript": "^5.3.3", "vite": "^5.0.12"
  }
}
```

`client/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020", "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext", "skipLibCheck": true,
    "moduleResolution": "bundler", "allowImportingTsExtensions": true,
    "resolveJsonModule": true, "isolatedModules": true,
    "noEmit": true, "jsx": "react-jsx", "strict": true
  },
  "include": ["src"]
}
```

`client/vite.config.ts`:
```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:3000' } },
});
```

`client/index.html`:
```html
<!DOCTYPE html>
<html lang="en" class="dark">
  <head><meta charset="UTF-8" /><title>KKC Admin</title></head>
  <body class="bg-gray-950 text-gray-100">
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: Init Tailwind + install**

```bash
cd "C:\Scripts\kkc-admin\client" && npx tailwindcss init -p
```

`client/tailwind.config.js`:
```js
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: { extend: {} },
  plugins: [],
};
```

`client/src/index.css`:
```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

```bash
cd "C:\Scripts\kkc-admin" && npm install
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: scaffold kkc-admin monorepo"
```

---

## Task 2: Server Types + Config + Device ID

**Files:**
- Create: `server/src/types.ts`
- Create: `server/src/config.ts`
- Create: `server/src/deviceId.ts`

- [ ] **Step 1: Write types.ts**

`server/src/types.ts`:
```typescript
export interface Config { basePath: string; port: number; }
export type WorkMode = 'CNC' | 'HARDWOODS' | 'ASSEMBLY';

export interface TrackerAction {
  file: string; page: number; part?: number;
  action: 'complete' | 'skip' | 'bad_part' | 'unskip' | 'unbad_part';
  timestamp: string; fileFingerprint?: string;
}
export interface TabletProgress { tabletId: string; actions: TrackerAction[]; }

export type SheetStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETE' | 'SKIPPED' | 'HAS_BAD_PARTS';
export interface PageStatus { page: number; status: SheetStatus; badParts: number[]; }

export interface MaterialProgress {
  pdfFilename: string; label: string;
  totalPages: number; done: number; bad: number; skipped: number;
  pages: PageStatus[];
}
export interface ModeProgress {
  totalSheets: number; done: number; bad: number; skipped: number; percentDone: number;
}
export interface JobSummary {
  folderName: string; jobNumber: string; jobName: string;
  hiddenFromProduction: boolean;
  cnc: ModeProgress; hardwoods: ModeProgress; assembly: ModeProgress;
}
export interface JobDetail extends JobSummary {
  materials: MaterialProgress[];
  assemblyMaterials: MaterialProgress[];
}

export interface OcrBox { left: number; top: number; right: number; bottom: number; }
export interface PartMetadata {
  number: number; name: string;
  width: number | null; length: number | null;
  cabNumber: number | null; room: string | null;
}
export interface PageMetadata {
  pageNumber: number; parts: PartMetadata[];
  ocrBoxes: Record<string, OcrBox[]>;
}
export interface PdfMetadata { pdfFilename: string; material: string; pages: PageMetadata[]; }

export interface RipItem {
  id: string; category: string; material: string;
  width: string; ripsNeeded: number; notes?: string;
  createdAt: string; createdBy: string;
}
export interface ChecklistItem {
  id: string; text: string; modes: WorkMode[]; notes?: string;
  createdAt: string; createdBy: string;
  completedAt: string | null; completedBy: string | null;
}
```

- [ ] **Step 2: Write config.ts**

`server/src/config.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { Config } from './types';

let _config: Config | null = null;

export function getConfig(): Config {
  if (_config) return _config;
  const configPath = path.join(__dirname, '../../config.json');
  if (!fs.existsSync(configPath)) {
    throw new Error(`config.json not found at ${configPath}. Copy config.example.json.`);
  }
  const raw = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
  if (!raw.basePath) throw new Error('config.json missing required field: basePath');
  _config = { basePath: raw.basePath, port: raw.port ?? 3000 };
  return _config;
}
```

- [ ] **Step 3: Write deviceId.ts**

`server/src/deviceId.ts`:
```typescript
import os from 'os';
export const deviceId = `admin-${os.hostname()}`;
```

- [ ] **Step 4: Commit**

```bash
git add server/src/types.ts server/src/config.ts server/src/deviceId.ts
git commit -m "feat: server types, config loader, device ID"
```

---

## Task 3: Job Scanner

**Files:**
- Create: `server/src/lib/jobScanner.ts`
- Create: `server/__tests__/jobScanner.test.ts`

- [ ] **Step 1: Write failing test**

`server/__tests__/jobScanner.test.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import os from 'os';
import { scanJobs } from '../src/lib/jobScanner';

describe('scanJobs', () => {
  let tmp: string;
  beforeEach(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kkc-')); });
  afterEach(() => { fs.rmSync(tmp, { recursive: true }); });

  function makeJob(name: string, gate: object, indexFields?: object) {
    const meta = path.join(tmp, name, '.metadata');
    fs.mkdirSync(meta, { recursive: true });
    fs.writeFileSync(path.join(meta, 'deployment_gate.json'), JSON.stringify(gate));
    if (indexFields) {
      fs.writeFileSync(path.join(meta, 'cabinet_sheet_index.json'),
        JSON.stringify({ documents: { delivery: { knownFields: indexFields } } }));
    }
  }

  it('returns jobs with deployment_gate.json', async () => {
    makeJob('Job1042', { hiddenFromProduction: false });
    const jobs = await scanJobs(tmp);
    expect(jobs).toHaveLength(1);
    expect(jobs[0].folderName).toBe('Job1042');
  });

  it('ignores folders without deployment_gate.json', async () => {
    fs.mkdirSync(path.join(tmp, 'NotAJob'));
    expect(await scanJobs(tmp)).toHaveLength(0);
  });

  it('excludes hiddenFromProduction=true jobs', async () => {
    makeJob('Job1042', { hiddenFromProduction: true });
    expect(await scanJobs(tmp)).toHaveLength(0);
  });

  it('reads jobNumber and jobName from cabinet_sheet_index', async () => {
    makeJob('Job1042', { hiddenFromProduction: false },
      { jobNumber: '1042', jobName: 'Smith Kitchen' });
    const jobs = await scanJobs(tmp);
    expect(jobs[0].jobNumber).toBe('1042');
    expect(jobs[0].jobName).toBe('Smith Kitchen');
  });
});
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest jobScanner --no-coverage
```
Expected: `Cannot find module '../src/lib/jobScanner'`

- [ ] **Step 3: Implement jobScanner.ts**

`server/src/lib/jobScanner.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { JobSummary, ModeProgress } from '../types';

const EMPTY: ModeProgress = { totalSheets: 0, done: 0, bad: 0, skipped: 0, percentDone: 0 };

export async function scanJobs(basePath: string): Promise<JobSummary[]> {
  const entries = fs.readdirSync(basePath, { withFileTypes: true });
  const results: JobSummary[] = [];

  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const folder = path.join(basePath, entry.name);
    const gatePath = path.join(folder, '.metadata', 'deployment_gate.json');
    if (!fs.existsSync(gatePath)) continue;

    const gate = JSON.parse(fs.readFileSync(gatePath, 'utf-8'));
    if (gate.hiddenFromProduction) continue;

    let jobNumber = gate.jobNumber ?? '';
    let jobName = gate.jobName ?? entry.name;

    const indexPath = path.join(folder, '.metadata', 'cabinet_sheet_index.json');
    if (fs.existsSync(indexPath)) {
      try {
        const idx = JSON.parse(fs.readFileSync(indexPath, 'utf-8'));
        const f = idx?.documents?.delivery?.knownFields;
        if (f?.jobNumber) jobNumber = f.jobNumber;
        if (f?.jobName) jobName = f.jobName;
      } catch { /* ignore */ }
    }

    results.push({
      folderName: entry.name, jobNumber, jobName,
      hiddenFromProduction: false,
      cnc: { ...EMPTY }, hardwoods: { ...EMPTY }, assembly: { ...EMPTY },
    });
  }

  // Sort by folder name descending (newest job numbers first)
  return results.sort((a, b) => b.folderName.localeCompare(a.folderName));
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest jobScanner --no-coverage
```
Expected: `PASS __tests__/jobScanner.test.ts`

- [ ] **Step 5: Commit**

```bash
git add server/src/lib/jobScanner.ts server/__tests__/jobScanner.test.ts
git commit -m "feat: job scanner"
```

---

## Task 4: CNC Progress Reader

**Files:**
- Create: `server/src/lib/progressReader.ts`
- Create: `server/__tests__/progressReader.test.ts`

- [ ] **Step 1: Write failing test**

`server/__tests__/progressReader.test.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import os from 'os';
import { readCncProgress } from '../src/lib/progressReader';

describe('readCncProgress', () => {
  let tmp: string;
  beforeEach(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kkc-')); });
  afterEach(() => { fs.rmSync(tmp, { recursive: true }); });

  function writeTracker(id: string, actions: object[]) {
    const dir = path.join(tmp, 'CNC', '.tracker');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify({ tabletId: id, actions }));
  }

  it('returns empty when no tracker files', async () => {
    const r = await readCncProgress(tmp);
    expect(r.materials).toEqual([]);
    expect(r.mode.totalSheets).toBe(0);
  });

  it('counts completed sheets', async () => {
    writeTracker('t1', [
      { file: 'Cabinet.pdf', page: 1, action: 'complete', timestamp: '2026-01-01T00:00:00Z' },
      { file: 'Cabinet.pdf', page: 2, action: 'complete', timestamp: '2026-01-01T00:00:00Z' },
    ]);
    const r = await readCncProgress(tmp);
    const mat = r.materials.find(m => m.pdfFilename === 'Cabinet.pdf')!;
    expect(mat.done).toBe(2);
    expect(mat.pages.find(p => p.page === 1)?.status).toBe('COMPLETE');
  });

  it('marks page SKIPPED', async () => {
    writeTracker('t1', [{ file: 'Upper.pdf', page: 3, action: 'skip', timestamp: '2026-01-01T00:00:00Z' }]);
    const r = await readCncProgress(tmp);
    const mat = r.materials.find(m => m.pdfFilename === 'Upper.pdf')!;
    expect(mat.pages.find(p => p.page === 3)?.status).toBe('SKIPPED');
    expect(mat.skipped).toBe(1);
  });

  it('merges across multiple tablet files', async () => {
    writeTracker('t1', [{ file: 'Cabinet.pdf', page: 1, action: 'complete', timestamp: '2026-01-01T00:00:00Z' }]);
    writeTracker('t2', [{ file: 'Cabinet.pdf', page: 2, action: 'complete', timestamp: '2026-01-01T00:00:00Z' }]);
    const r = await readCncProgress(tmp);
    expect(r.materials.find(m => m.pdfFilename === 'Cabinet.pdf')!.done).toBe(2);
  });

  it('records bad parts', async () => {
    writeTracker('t1', [{ file: 'Cabinet.pdf', page: 2, part: 3, action: 'bad_part', timestamp: '2026-01-01T00:00:00Z' }]);
    const r = await readCncProgress(tmp);
    const page = r.materials.find(m => m.pdfFilename === 'Cabinet.pdf')!.pages.find(p => p.page === 2)!;
    expect(page.badParts).toContain(3);
    expect(page.status).toBe('HAS_BAD_PARTS');
  });
});
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest progressReader --no-coverage
```

- [ ] **Step 3: Implement progressReader.ts**

`server/src/lib/progressReader.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { MaterialProgress, ModeProgress, PageStatus, SheetStatus, TabletProgress } from '../types';

interface CncProgressResult { materials: MaterialProgress[]; mode: ModeProgress; }

export async function readCncProgress(jobFolder: string): Promise<CncProgressResult> {
  const trackerDir = path.join(jobFolder, 'CNC', '.tracker');
  const pageMap = new Map<string, Map<number, { badParts: Set<number>; latestAction: string; timestamp: string }>>();

  if (fs.existsSync(trackerDir)) {
    for (const file of fs.readdirSync(trackerDir).filter(f => f.endsWith('.json'))) {
      try {
        const raw: TabletProgress = JSON.parse(fs.readFileSync(path.join(trackerDir, file), 'utf-8'));
        for (const a of (raw.actions ?? [])) {
          if (!pageMap.has(a.file)) pageMap.set(a.file, new Map());
          const pages = pageMap.get(a.file)!;
          if (!pages.has(a.page)) pages.set(a.page, { badParts: new Set(), latestAction: '', timestamp: '' });
          const ps = pages.get(a.page)!;
          if (a.action === 'bad_part' && a.part != null) ps.badParts.add(a.part);
          else if (a.action === 'unbad_part' && a.part != null) ps.badParts.delete(a.part);
          else if (['complete', 'skip', 'unskip'].includes(a.action)) {
            if (!ps.timestamp || a.timestamp > ps.timestamp) {
              ps.latestAction = a.action; ps.timestamp = a.timestamp;
            }
          }
        }
      } catch { /* skip corrupt */ }
    }
  }

  const materials: MaterialProgress[] = [];
  let totalDone = 0, totalBad = 0, totalSkipped = 0, totalSheets = 0;

  for (const [pdf, pages] of pageMap) {
    const pageStatuses: PageStatus[] = [];
    let done = 0, bad = 0, skipped = 0;
    for (const [page, state] of pages) {
      let status: SheetStatus = 'NOT_STARTED';
      if (state.latestAction === 'complete') status = 'COMPLETE';
      else if (state.latestAction === 'skip') status = 'SKIPPED';
      if (state.badParts.size > 0) status = 'HAS_BAD_PARTS';
      if (status === 'COMPLETE') done++;
      else if (status === 'SKIPPED') skipped++;
      else if (status === 'HAS_BAD_PARTS') bad++;
      pageStatuses.push({ page, status, badParts: Array.from(state.badParts) });
    }
    pageStatuses.sort((a, b) => a.page - b.page);
    totalDone += done; totalBad += bad; totalSkipped += skipped; totalSheets += pageStatuses.length;
    materials.push({ pdfFilename: pdf, label: pdf, totalPages: pageStatuses.length, done, bad, skipped, pages: pageStatuses });
  }

  const percentDone = totalSheets > 0 ? Math.round((totalDone / totalSheets) * 100) : 0;
  return { materials, mode: { totalSheets, done: totalDone, bad: totalBad, skipped: totalSkipped, percentDone } };
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest progressReader --no-coverage
```

- [ ] **Step 5: Commit**

```bash
git add server/src/lib/progressReader.ts server/__tests__/progressReader.test.ts
git commit -m "feat: CNC progress reader"
```

---

## Task 5: Hardwoods Reader

**Files:** Create: `server/src/lib/hardwoodsReader.ts`

- [ ] **Step 1: Implement**

`server/src/lib/hardwoodsReader.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { ModeProgress } from '../types';

interface HWAction { docType: string; rowId: string; action: string; value?: number; timestamp: string; }

export async function readHardwoodsProgress(jobFolder: string): Promise<ModeProgress> {
  const empty: ModeProgress = { totalSheets: 0, done: 0, bad: 0, skipped: 0, percentDone: 0 };
  const indexPath = path.join(jobFolder, '.metadata', 'hardwoods', 'cutlist_index.json');
  const trackerDir = path.join(jobFolder, '.metadata', 'hardwoods', '.tracker');

  if (!fs.existsSync(indexPath)) return empty;
  let totalRows = 0;
  try {
    const idx = JSON.parse(fs.readFileSync(indexPath, 'utf-8'));
    for (const doc of (idx.documents ?? [])) totalRows += (doc.rows ?? []).length;
  } catch { return empty; }
  if (totalRows === 0) return empty;

  const doneCount = new Map<string, number>();
  const skipped = new Set<string>();

  if (fs.existsSync(trackerDir)) {
    for (const file of fs.readdirSync(trackerDir).filter(f => f.endsWith('.json'))) {
      try {
        const raw = JSON.parse(fs.readFileSync(path.join(trackerDir, file), 'utf-8'));
        for (const a of (raw.actions ?? []) as HWAction[]) {
          const key = `${a.docType}|${a.rowId}`;
          if (a.action === 'SET_DONE_COUNT') doneCount.set(key, a.value ?? 0);
          else if (a.action === 'SET_SKIPPED') skipped.add(key);
          else if (a.action === 'CLEAR_SKIPPED') skipped.delete(key);
        }
      } catch { /* skip */ }
    }
  }

  const doneRows = Math.min([...doneCount.values()].filter(v => v > 0).length + skipped.size, totalRows);
  return { totalSheets: totalRows, done: doneRows, bad: 0, skipped: skipped.size, percentDone: Math.round((doneRows / totalRows) * 100) };
}
```

- [ ] **Step 2: Commit**

```bash
git add server/src/lib/hardwoodsReader.ts && git commit -m "feat: hardwoods progress reader"
```

---

## Task 6: PDF Metadata Reader

**Files:** Create: `server/src/lib/metadataReader.ts`

- [ ] **Step 1: Implement**

`server/src/lib/metadataReader.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { PdfMetadata } from '../types';

export function readPdfMetadata(jobFolder: string, pdfFilename: string): PdfMetadata | null {
  const stem = pdfFilename.replace(/\.pdf$/i, '');
  const p = path.join(jobFolder, 'CNC', '.metadata', `${stem}.json`);
  if (!fs.existsSync(p)) return null;
  try { return JSON.parse(fs.readFileSync(p, 'utf-8')) as PdfMetadata; } catch { return null; }
}

export function readAllPdfMetadata(jobFolder: string): PdfMetadata[] {
  const dir = path.join(jobFolder, 'CNC', '.metadata');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => f.endsWith('.json')).flatMap(f => {
    try { return [JSON.parse(fs.readFileSync(path.join(dir, f), 'utf-8')) as PdfMetadata]; }
    catch { return []; }
  });
}
```

- [ ] **Step 2: Commit**

```bash
git add server/src/lib/metadataReader.ts && git commit -m "feat: PDF metadata reader"
```

---

## Task 7: Admin Store (Rip Items + Checklist)

**Files:**
- Create: `server/src/lib/adminStore.ts`
- Create: `server/__tests__/adminStore.test.ts`

- [ ] **Step 1: Write failing tests**

`server/__tests__/adminStore.test.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import os from 'os';
import {
  getRipItems, addRipItem, deleteRipItem,
  getChecklist, addChecklistItem, toggleChecklistItem, deleteChecklistItem,
} from '../src/lib/adminStore';

describe('adminStore', () => {
  let tmp: string;
  beforeEach(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kkc-')); });
  afterEach(() => { fs.rmSync(tmp, { recursive: true }); });

  it('getRipItems returns [] when no file', () => expect(getRipItems(tmp)).toEqual([]));

  it('addRipItem creates item with id', () => {
    addRipItem(tmp, { category: 'Base', material: 'Maple', width: '3.5', ripsNeeded: 4 }, 'admin-PC');
    const items = getRipItems(tmp);
    expect(items).toHaveLength(1);
    expect(items[0].category).toBe('Base');
    expect(items[0].id).toBeTruthy();
  });

  it('deleteRipItem removes by id', () => {
    addRipItem(tmp, { category: 'Crown', material: 'Oak', width: '4', ripsNeeded: 2 }, 'admin-PC');
    deleteRipItem(tmp, getRipItems(tmp)[0].id);
    expect(getRipItems(tmp)).toHaveLength(0);
  });

  it('getChecklist returns [] when no file', () => expect(getChecklist(tmp)).toEqual([]));

  it('addChecklistItem creates with null completion', () => {
    addChecklistItem(tmp, { text: 'Check swing', modes: ['CNC'], notes: '' }, 'admin-PC');
    const items = getChecklist(tmp);
    expect(items[0].completedAt).toBeNull();
  });

  it('toggleChecklistItem marks complete then uncomplete', () => {
    addChecklistItem(tmp, { text: 'Test', modes: ['CNC'], notes: '' }, 'admin-PC');
    const id = getChecklist(tmp)[0].id;
    toggleChecklistItem(tmp, id, 'admin-PC');
    expect(getChecklist(tmp)[0].completedAt).not.toBeNull();
    toggleChecklistItem(tmp, id, 'admin-PC');
    expect(getChecklist(tmp)[0].completedAt).toBeNull();
  });

  it('deleteChecklistItem removes item', () => {
    addChecklistItem(tmp, { text: 'Test', modes: ['CNC'], notes: '' }, 'admin-PC');
    deleteChecklistItem(tmp, getChecklist(tmp)[0].id);
    expect(getChecklist(tmp)).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest adminStore --no-coverage
```

- [ ] **Step 3: Implement adminStore.ts**

`server/src/lib/adminStore.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { v4 as uuidv4 } from 'uuid';
import { RipItem, ChecklistItem, WorkMode } from '../types';

const adminDir = (f: string) => path.join(f, '.metadata', 'admin');
const ripPath  = (f: string) => path.join(adminDir(f), 'rip_items.json');
const chkPath  = (f: string) => path.join(adminDir(f), 'checklist.json');

function readJson<T>(p: string, def: T): T {
  if (!fs.existsSync(p)) return def;
  try { return JSON.parse(fs.readFileSync(p, 'utf-8')); } catch { return def; }
}
function writeJson(p: string, data: unknown) {
  const tmp = p + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, p);
}

export function getRipItems(jobFolder: string): RipItem[] {
  return readJson<{ items: RipItem[] }>(ripPath(jobFolder), { items: [] }).items;
}
export function addRipItem(
  jobFolder: string,
  input: { category: string; material: string; width: string; ripsNeeded: number; notes?: string },
  createdBy: string,
): RipItem {
  fs.mkdirSync(adminDir(jobFolder), { recursive: true });
  const item: RipItem = { id: uuidv4(), ...input, createdAt: new Date().toISOString(), createdBy };
  writeJson(ripPath(jobFolder), { schemaVersion: 1, items: [...getRipItems(jobFolder), item] });
  return item;
}
export function deleteRipItem(jobFolder: string, id: string): void {
  writeJson(ripPath(jobFolder), { schemaVersion: 1, items: getRipItems(jobFolder).filter(i => i.id !== id) });
}

export function getChecklist(jobFolder: string): ChecklistItem[] {
  return readJson<{ items: ChecklistItem[] }>(chkPath(jobFolder), { items: [] }).items;
}
export function addChecklistItem(
  jobFolder: string,
  input: { text: string; modes: WorkMode[]; notes?: string },
  createdBy: string,
): ChecklistItem {
  fs.mkdirSync(adminDir(jobFolder), { recursive: true });
  const item: ChecklistItem = {
    id: uuidv4(), ...input, notes: input.notes ?? '',
    createdAt: new Date().toISOString(), createdBy,
    completedAt: null, completedBy: null,
  };
  writeJson(chkPath(jobFolder), { schemaVersion: 1, items: [...getChecklist(jobFolder), item] });
  return item;
}
export function toggleChecklistItem(jobFolder: string, id: string, deviceId: string): void {
  const items = getChecklist(jobFolder).map(i => i.id !== id ? i
    : i.completedAt
      ? { ...i, completedAt: null, completedBy: null }
      : { ...i, completedAt: new Date().toISOString(), completedBy: deviceId });
  writeJson(chkPath(jobFolder), { schemaVersion: 1, items });
}
export function deleteChecklistItem(jobFolder: string, id: string): void {
  writeJson(chkPath(jobFolder), { schemaVersion: 1, items: getChecklist(jobFolder).filter(i => i.id !== id) });
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest adminStore --no-coverage
```

- [ ] **Step 5: Commit**

```bash
git add server/src/lib/adminStore.ts server/__tests__/adminStore.test.ts
git commit -m "feat: admin store — rip items + checklist CRUD"
```

---

## Task 8: Tracker Writer

**Files:**
- Create: `server/src/lib/trackerWriter.ts`
- Create: `server/__tests__/trackerWriter.test.ts`

- [ ] **Step 1: Write failing test**

`server/__tests__/trackerWriter.test.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import os from 'os';
import { writeTrackerAction } from '../src/lib/trackerWriter';

describe('writeTrackerAction', () => {
  let tmp: string;
  beforeEach(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kkc-')); });
  afterEach(() => { fs.rmSync(tmp, { recursive: true }); });

  it('creates tracker file with action', () => {
    writeTrackerAction(tmp, 'admin-PC', { file: 'Cabinet.pdf', page: 1, action: 'complete', timestamp: '2026-01-01T00:00:00Z' });
    const p = path.join(tmp, 'CNC', '.tracker', 'admin-PC.json');
    const data = JSON.parse(fs.readFileSync(p, 'utf-8'));
    expect(data.tabletId).toBe('admin-PC');
    expect(data.actions[0].action).toBe('complete');
  });

  it('appends to existing file', () => {
    writeTrackerAction(tmp, 'admin-PC', { file: 'A.pdf', page: 1, action: 'complete', timestamp: '2026-01-01T00:00:00Z' });
    writeTrackerAction(tmp, 'admin-PC', { file: 'A.pdf', page: 2, action: 'skip', timestamp: '2026-01-01T00:00:01Z' });
    const data = JSON.parse(fs.readFileSync(path.join(tmp, 'CNC', '.tracker', 'admin-PC.json'), 'utf-8'));
    expect(data.actions).toHaveLength(2);
  });
});
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest trackerWriter --no-coverage
```

- [ ] **Step 3: Implement trackerWriter.ts**

`server/src/lib/trackerWriter.ts`:
```typescript
import fs from 'fs';
import path from 'path';
import { TrackerAction, TabletProgress } from '../types';

export function writeTrackerAction(jobFolder: string, deviceId: string, action: TrackerAction): void {
  const dir = path.join(jobFolder, 'CNC', '.tracker');
  fs.mkdirSync(dir, { recursive: true });
  const filePath = path.join(dir, `${deviceId}.json`);
  let progress: TabletProgress = { tabletId: deviceId, actions: [] };
  if (fs.existsSync(filePath)) {
    try { progress = JSON.parse(fs.readFileSync(filePath, 'utf-8')); } catch { /* start fresh */ }
  }
  progress.actions.push(action);
  const tmp = filePath + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(progress, null, 2));
  fs.renameSync(tmp, filePath);
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest trackerWriter --no-coverage
```

- [ ] **Step 5: Commit**

```bash
git add server/src/lib/trackerWriter.ts server/__tests__/trackerWriter.test.ts
git commit -m "feat: tracker writer"
```

---

## Task 9: API Routes + Express Entry

**Files:**
- Create: `server/src/routes/jobs.ts`
- Create: `server/src/routes/pdf.ts`
- Create: `server/src/routes/tracker.ts`
- Create: `server/src/routes/admin.ts`
- Create: `server/src/index.ts`

- [ ] **Step 1: jobs router** — `server/src/routes/jobs.ts`:

```typescript
import { Router } from 'express';
import path from 'path';
import { getConfig } from '../config';
import { scanJobs } from '../lib/jobScanner';
import { readCncProgress } from '../lib/progressReader';
import { readHardwoodsProgress } from '../lib/hardwoodsReader';

const router = Router();

router.get('/', async (_req, res) => {
  try {
    const { basePath } = getConfig();
    const jobs = await scanJobs(basePath);
    const withProgress = await Promise.all(jobs.map(async job => {
      const folder = path.join(basePath, job.folderName);
      const [cnc, hardwoods] = await Promise.all([readCncProgress(folder), readHardwoodsProgress(folder)]);
      return { ...job, cnc: cnc.mode, hardwoods };
    }));
    res.json(withProgress);
  } catch (err) { res.status(500).json({ error: String(err) }); }
});

router.get('/:folder', async (req, res) => {
  try {
    const folder = path.join(getConfig().basePath, req.params.folder);
    const [cncResult, hardwoods] = await Promise.all([readCncProgress(folder), readHardwoodsProgress(folder)]);
    res.json({
      folderName: req.params.folder,
      cnc: cncResult.mode, hardwoods,
      assembly: { totalSheets: 0, done: 0, bad: 0, skipped: 0, percentDone: 0 },
      materials: cncResult.materials,
      assemblyMaterials: [],
    });
  } catch (err) { res.status(500).json({ error: String(err) }); }
});

export default router;
```

- [ ] **Step 2: pdf router** — `server/src/routes/pdf.ts`:

```typescript
import { Router } from 'express';
import path from 'path';
import fs from 'fs';
import { getConfig } from '../config';

const router = Router();

router.get('/:folder/:filename', (req, res) => {
  const { basePath } = getConfig();
  const filePath = path.resolve(basePath, req.params.folder, req.params.filename);
  if (!filePath.startsWith(path.resolve(basePath))) return res.status(403).send('Forbidden');
  if (!fs.existsSync(filePath)) return res.status(404).send('Not found');
  res.setHeader('Content-Type', 'application/pdf');
  fs.createReadStream(filePath).pipe(res);
});

export default router;
```

- [ ] **Step 3: tracker router** — `server/src/routes/tracker.ts`:

```typescript
import { Router } from 'express';
import path from 'path';
import { getConfig } from '../config';
import { deviceId } from '../deviceId';
import { writeTrackerAction } from '../lib/trackerWriter';
import { TrackerAction } from '../types';

const router = Router({ mergeParams: true });

router.post('/', (req, res) => {
  try {
    const folder = path.join(getConfig().basePath, req.params.folder);
    const { file, page, action, part, fileFingerprint } = req.body as Partial<TrackerAction>;
    if (!file || page == null || !action) return res.status(400).json({ error: 'Missing file, page, or action' });
    writeTrackerAction(folder, deviceId, {
      file, page, action: action as TrackerAction['action'],
      timestamp: new Date().toISOString(),
      ...(part != null && { part }),
      ...(fileFingerprint && { fileFingerprint }),
    });
    res.json({ ok: true, deviceId });
  } catch (err) { res.status(500).json({ error: String(err) }); }
});

export default router;
```

- [ ] **Step 4: admin router** — `server/src/routes/admin.ts`:

```typescript
import { Router } from 'express';
import path from 'path';
import { getConfig } from '../config';
import { deviceId } from '../deviceId';
import { getRipItems, addRipItem, deleteRipItem, getChecklist, addChecklistItem, toggleChecklistItem, deleteChecklistItem } from '../lib/adminStore';
import { WorkMode } from '../types';

const router = Router({ mergeParams: true });
const folder = (req: { params: { folder: string } }) => path.join(getConfig().basePath, req.params.folder);

router.get('/rip-items',       (req, res) => res.json(getRipItems(folder(req))));
router.delete('/rip-items/:id',(req, res) => { deleteRipItem(folder(req), req.params.id); res.json({ ok: true }); });
router.post('/rip-items', (req, res) => {
  const { category, material, width, ripsNeeded, notes } = req.body;
  if (!category || !material || !width || ripsNeeded == null) return res.status(400).json({ error: 'Missing fields' });
  res.status(201).json(addRipItem(folder(req), { category, material, width, ripsNeeded, notes }, deviceId));
});

router.get('/checklist',           (req, res) => res.json(getChecklist(folder(req))));
router.delete('/checklist/:id',    (req, res) => { deleteChecklistItem(folder(req), req.params.id); res.json({ ok: true }); });
router.patch('/checklist/:id',     (req, res) => { toggleChecklistItem(folder(req), req.params.id, deviceId); res.json({ ok: true }); });
router.post('/checklist', (req, res) => {
  const { text, modes, notes } = req.body;
  if (!text || !modes?.length) return res.status(400).json({ error: 'Missing text or modes' });
  res.status(201).json(addChecklistItem(folder(req), { text, modes: modes as WorkMode[], notes }, deviceId));
});

export default router;
```

- [ ] **Step 5: Wire up index.ts** — `server/src/index.ts`:

```typescript
import express from 'express';
import path from 'path';
import { getConfig } from './config';
import { deviceId } from './deviceId';
import jobsRouter from './routes/jobs';
import pdfRouter from './routes/pdf';
import trackerRouter from './routes/tracker';
import adminRouter from './routes/admin';

const app = express();
app.use(express.json());

app.get('/api/health', (_req, res) => res.json({ ok: true, deviceId }));
app.use('/api/jobs', jobsRouter);
app.use('/api/jobs/:folder/tracker', trackerRouter);
app.use('/api/jobs/:folder', adminRouter);
app.use('/api/pdf', pdfRouter);

const clientDist = path.join(__dirname, '../public');
app.use(express.static(clientDist));
app.get('*', (_req, res) => res.sendFile(path.join(clientDist, 'index.html')));

const { port } = getConfig();
app.listen(port, () => console.log(`KKC Admin  http://localhost:${port}  [${deviceId}]`));
```

- [ ] **Step 6: Verify server starts**

```bash
cd "C:\Scripts\kkc-admin\server" && npx ts-node-dev src/index.ts
```
Expected: `KKC Admin  http://localhost:3000  [admin-YOURPC]`
Visit `http://localhost:3000/api/health` → `{"ok":true,"deviceId":"admin-YOURPC"}`

- [ ] **Step 7: Commit**

```bash
git add server/src/routes server/src/index.ts
git commit -m "feat: API routes + Express server"
```

---

## Task 10: Client Shell — App, Types, API, Hooks

**Files:**
- Create: `client/src/main.tsx`
- Create: `client/src/App.tsx`
- Create: `client/src/types.ts`
- Create: `client/src/api.ts`
- Create: `client/src/hooks/useJobs.ts`
- Create: `client/src/hooks/useJob.ts`
- Create: `client/src/hooks/useChecklist.ts`
- Create: `client/src/hooks/useRipItems.ts`

- [ ] **Step 1: main.tsx + App.tsx stub**

`client/src/main.tsx`:
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><App /></React.StrictMode>
);
```

`client/src/App.tsx` (stub — fill out in Task 11):
```tsx
import React, { useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { useJobs } from './hooks/useJobs';
import { JobSummary } from './types';

export default function App() {
  const { jobs, loading, error } = useJobs();
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);

  if (loading) return <div className="flex items-center justify-center h-screen text-gray-400">Loading jobs...</div>;
  if (error)   return <div className="flex items-center justify-center h-screen text-red-400">Error: {error}</div>;

  return (
    <div className="flex h-screen bg-gray-950 text-gray-100 overflow-hidden">
      <Sidebar jobs={jobs} selectedFolder={selectedFolder} onSelect={setSelectedFolder} />
      <main className="flex-1 overflow-hidden">
        {selectedFolder
          ? <div className="p-4 text-gray-400">Job detail coming in Task 11</div>
          : <div className="flex items-center justify-center h-full text-gray-600">Select a job</div>
        }
      </main>
    </div>
  );
}
```

- [ ] **Step 2: types.ts** — `client/src/types.ts`:

```typescript
export type WorkMode = 'CNC' | 'HARDWOODS' | 'ASSEMBLY';
export type SheetStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETE' | 'SKIPPED' | 'HAS_BAD_PARTS';

export interface ModeProgress { totalSheets: number; done: number; bad: number; skipped: number; percentDone: number; }
export interface PageStatus { page: number; status: SheetStatus; badParts: number[]; }
export interface MaterialProgress { pdfFilename: string; label: string; totalPages: number; done: number; bad: number; skipped: number; pages: PageStatus[]; }

export interface JobSummary {
  folderName: string; jobNumber: string; jobName: string;
  cnc: ModeProgress; hardwoods: ModeProgress; assembly: ModeProgress;
}
export interface JobDetail extends JobSummary {
  materials: MaterialProgress[];
  assemblyMaterials: MaterialProgress[];
}

export interface OcrBox { left: number; top: number; right: number; bottom: number; }
export interface PartMetadata { number: number; name: string; width: number|null; length: number|null; cabNumber: number|null; room: string|null; }
export interface PageMetadata { pageNumber: number; parts: PartMetadata[]; ocrBoxes: Record<string, OcrBox[]>; }
export interface PdfMetadata { pdfFilename: string; material: string; pages: PageMetadata[]; }

export interface RipItem { id: string; category: string; material: string; width: string; ripsNeeded: number; notes?: string; createdAt: string; createdBy: string; }
export interface ChecklistItem { id: string; text: string; modes: WorkMode[]; notes?: string; createdAt: string; createdBy: string; completedAt: string|null; completedBy: string|null; }
```

- [ ] **Step 3: api.ts** — `client/src/api.ts`:

```typescript
import { JobSummary, JobDetail, RipItem, ChecklistItem, WorkMode } from './types';

const base = '/api';

export const api = {
  jobs: {
    list: (): Promise<JobSummary[]> => fetch(`${base}/jobs`).then(r => r.json()),
    get:  (folder: string): Promise<JobDetail> => fetch(`${base}/jobs/${folder}`).then(r => r.json()),
  },
  tracker: {
    post: (folder: string, body: { file: string; page: number; action: string; part?: number; fileFingerprint?: string }) =>
      fetch(`${base}/jobs/${folder}/tracker`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(r => r.json()),
  },
  ripItems: {
    list:   (folder: string): Promise<RipItem[]> => fetch(`${base}/jobs/${folder}/rip-items`).then(r => r.json()),
    add:    (folder: string, body: Omit<RipItem, 'id'|'createdAt'|'createdBy'>) =>
      fetch(`${base}/jobs/${folder}/rip-items`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(r => r.json()),
    delete: (folder: string, id: string) => fetch(`${base}/jobs/${folder}/rip-items/${id}`, { method: 'DELETE' }),
  },
  checklist: {
    list:   (folder: string): Promise<ChecklistItem[]> => fetch(`${base}/jobs/${folder}/checklist`).then(r => r.json()),
    add:    (folder: string, body: { text: string; modes: WorkMode[]; notes?: string }) =>
      fetch(`${base}/jobs/${folder}/checklist`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(r => r.json()),
    toggle: (folder: string, id: string) => fetch(`${base}/jobs/${folder}/checklist/${id}`, { method: 'PATCH' }),
    delete: (folder: string, id: string) => fetch(`${base}/jobs/${folder}/checklist/${id}`, { method: 'DELETE' }),
  },
};
```

- [ ] **Step 4: hooks** — four files:

`client/src/hooks/useJobs.ts`:
```typescript
import { useState, useEffect, useCallback } from 'react';
import { api } from '../api';
import { JobSummary } from '../types';

export function useJobs() {
  const [jobs, setJobs] = useState<JobSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    try { setJobs(await api.jobs.list()); setError(null); }
    catch (e) { setError(String(e)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    fetch();
    const id = setInterval(fetch, 30_000);
    return () => clearInterval(id);
  }, [fetch]);

  return { jobs, loading, error, refresh: fetch };
}
```

`client/src/hooks/useJob.ts`:
```typescript
import { useState, useEffect } from 'react';
import { api } from '../api';
import { JobDetail } from '../types';

export function useJob(folder: string | null) {
  const [job, setJob] = useState<JobDetail | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!folder) { setJob(null); return; }
    setLoading(true);
    api.jobs.get(folder).then(j => { setJob(j); setLoading(false); });
  }, [folder]);

  const refresh = () => { if (folder) api.jobs.get(folder).then(setJob); };
  return { job, loading, refresh };
}
```

`client/src/hooks/useRipItems.ts`:
```typescript
import { useState, useEffect, useCallback } from 'react';
import { api } from '../api';
import { RipItem } from '../types';

export function useRipItems(folder: string | null) {
  const [items, setItems] = useState<RipItem[]>([]);
  const refresh = useCallback(() => { if (folder) api.ripItems.list(folder).then(setItems); }, [folder]);
  useEffect(() => { refresh(); }, [refresh]);
  return { items, refresh };
}
```

`client/src/hooks/useChecklist.ts`:
```typescript
import { useState, useEffect, useCallback } from 'react';
import { api } from '../api';
import { ChecklistItem } from '../types';

export function useChecklist(folder: string | null) {
  const [items, setItems] = useState<ChecklistItem[]>([]);
  const refresh = useCallback(() => { if (folder) api.checklist.list(folder).then(setItems); }, [folder]);
  useEffect(() => { refresh(); }, [refresh]);
  return { items, refresh };
}
```

- [ ] **Step 5: Verify client starts**

```bash
cd "C:\Scripts\kkc-admin" && npm run dev
```
Open `http://localhost:5173` — expect "Loading jobs..." then sidebar or "Select a job".

- [ ] **Step 6: Commit**

```bash
git add client/src && git commit -m "feat: client shell — types, api, hooks, App stub"
```

---

## Task 11: Sidebar + ModePanel Header

**Files:**
- Create: `client/src/components/Sidebar.tsx`
- Create: `client/src/components/JobCard.tsx`
- Create: `client/src/components/MiniProgressBars.tsx`
- Create: `client/src/components/ModeTabs.tsx`
- Modify: `client/src/App.tsx`

- [ ] **Step 1: MiniProgressBars.tsx**

`client/src/components/MiniProgressBars.tsx`:
```tsx
import React from 'react';
import { ModeProgress } from '../types';

interface Props { cnc: ModeProgress; hardwoods: ModeProgress; assembly: ModeProgress; }

function Bar({ label, pct }: { label: string; pct: number }) {
  const color = pct === 100 ? 'bg-green-500' : pct > 0 ? 'bg-amber-500' : 'bg-gray-700';
  return (
    <div className="flex items-center gap-1">
      <span className="text-gray-500 text-[9px] w-6 flex-shrink-0">{label}</span>
      <div className="flex-1 h-1.5 bg-gray-800 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

export function MiniProgressBars({ cnc, hardwoods, assembly }: Props) {
  return (
    <div className="flex flex-col gap-0.5 mt-1.5">
      <Bar label="CNC" pct={cnc.percentDone} />
      <Bar label="HW"  pct={hardwoods.percentDone} />
      <Bar label="ASM" pct={assembly.percentDone} />
    </div>
  );
}
```

- [ ] **Step 2: JobCard.tsx**

`client/src/components/JobCard.tsx`:
```tsx
import React from 'react';
import { JobSummary } from '../types';
import { MiniProgressBars } from './MiniProgressBars';

interface Props { job: JobSummary; selected: boolean; onClick: () => void; }

export function JobCard({ job, selected, onClick }: Props) {
  return (
    <button
      onClick={onClick}
      className={`w-full text-left px-2.5 py-2 rounded mb-1 border-l-2 transition-colors ${
        selected ? 'bg-blue-950 border-blue-500' : 'bg-transparent border-transparent hover:bg-gray-800'
      }`}
    >
      <div className="flex justify-between items-baseline">
        <span className={`text-[11px] font-semibold truncate ${selected ? 'text-white' : 'text-gray-300'}`}>
          #{job.jobNumber} {job.jobName}
        </span>
        <span className="text-[9px] text-gray-500 ml-1 flex-shrink-0">{job.cnc.percentDone}%</span>
      </div>
      <MiniProgressBars cnc={job.cnc} hardwoods={job.hardwoods} assembly={job.assembly} />
    </button>
  );
}
```

- [ ] **Step 3: Sidebar.tsx**

`client/src/components/Sidebar.tsx`:
```tsx
import React, { useState } from 'react';
import { JobSummary } from '../types';
import { JobCard } from './JobCard';

interface Props { jobs: JobSummary[]; selectedFolder: string | null; onSelect: (folder: string) => void; }

export function Sidebar({ jobs, selectedFolder, onSelect }: Props) {
  const [search, setSearch] = useState('');
  const filtered = jobs.filter(j =>
    !search || j.jobName.toLowerCase().includes(search.toLowerCase()) || j.jobNumber.includes(search)
  );

  return (
    <div className="w-52 flex-shrink-0 bg-gray-900 border-r border-gray-800 flex flex-col h-full">
      <div className="p-3 border-b border-gray-800">
        <div className="text-white font-bold text-sm">KKC Admin</div>
        <div className="text-gray-600 text-[9px] mt-0.5">admin dashboard</div>
      </div>
      <div className="p-2 border-b border-gray-800">
        <input
          value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search jobs..."
          className="w-full bg-gray-800 text-gray-300 text-xs rounded px-2 py-1.5 outline-none placeholder-gray-600"
        />
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        {filtered.length === 0 && <div className="text-gray-600 text-xs text-center mt-4">No jobs found</div>}
        {filtered.map(j => (
          <JobCard key={j.folderName} job={j} selected={j.folderName === selectedFolder} onClick={() => onSelect(j.folderName)} />
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: ModeTabs.tsx**

`client/src/components/ModeTabs.tsx`:
```tsx
import React from 'react';

export type TabId = 'CNC' | 'HARDWOODS' | 'ASSEMBLY' | 'CHECKLIST';
interface Props { active: TabId; onChange: (t: TabId) => void; pendingChecklist: number; }

const TABS: { id: TabId; label: string }[] = [
  { id: 'CNC',       label: 'CNC' },
  { id: 'HARDWOODS', label: 'Hardwoods' },
  { id: 'ASSEMBLY',  label: 'Assembly' },
  { id: 'CHECKLIST', label: '☑ Checklist' },
];

export function ModeTabs({ active, onChange, pendingChecklist }: Props) {
  return (
    <div className="flex border-b border-gray-800 bg-gray-900 px-4">
      {TABS.map(t => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
            active === t.id ? 'border-blue-500 text-blue-400' : 'border-transparent text-gray-500 hover:text-gray-300'
          }`}
        >
          {t.label}
          {t.id === 'CHECKLIST' && pendingChecklist > 0 && (
            <span className="ml-1.5 bg-gray-700 text-gray-400 text-[9px] px-1.5 py-0.5 rounded-full">{pendingChecklist}</span>
          )}
        </button>
      ))}
    </div>
  );
}
```

- [ ] **Step 5: Update App.tsx to wire it together**

`client/src/App.tsx`:
```tsx
import React, { useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { ModeTabs, TabId } from './components/ModeTabs';
import { useJobs } from './hooks/useJobs';
import { useJob } from './hooks/useJob';
import { useChecklist } from './hooks/useChecklist';

export default function App() {
  const { jobs, loading, error } = useJobs();
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<TabId>('CNC');
  const { job } = useJob(selectedFolder);
  const { items: checklist } = useChecklist(selectedFolder);
  const pendingChecklist = checklist.filter(i => !i.completedAt).length;

  if (loading) return <div className="flex items-center justify-center h-screen text-gray-400">Loading...</div>;
  if (error)   return <div className="flex items-center justify-center h-screen text-red-400">{error}</div>;

  return (
    <div className="flex h-screen bg-gray-950 text-gray-100 overflow-hidden">
      <Sidebar jobs={jobs} selectedFolder={selectedFolder} onSelect={f => { setSelectedFolder(f); setActiveTab('CNC'); }} />
      <main className="flex-1 flex flex-col overflow-hidden">
        {job ? (
          <>
            <div className="bg-gray-900 border-b border-gray-800 px-4 py-2.5 flex items-center gap-3">
              <div>
                <div className="text-white font-bold text-sm">#{job.jobNumber} — {job.jobName}</div>
                <div className="text-gray-500 text-[10px]">{job.folderName}</div>
              </div>
            </div>
            <ModeTabs active={activeTab} onChange={setActiveTab} pendingChecklist={pendingChecklist} />
            <div className="flex-1 overflow-y-auto p-4">
              <div className="text-gray-400 text-sm">Tab content — Tasks 12–14</div>
            </div>
          </>
        ) : (
          <div className="flex items-center justify-center h-full text-gray-600">Select a job from the sidebar</div>
        )}
      </main>
    </div>
  );
}
```

- [ ] **Step 6: Verify in browser**

```bash
cd "C:\Scripts\kkc-admin" && npm run dev
```
Open `http://localhost:5173`. Sidebar should show jobs with 3 mini bars. Clicking a job shows job name + mode tabs.

- [ ] **Step 7: Commit**

```bash
git add client/src && git commit -m "feat: sidebar, job cards, mode tabs"
```

---

## Task 12: CNC Tab — Progress Bar + Material Cards

**Files:**
- Create: `client/src/components/ProgressBar.tsx`
- Create: `client/src/components/StatusChip.tsx`
- Create: `client/src/components/PageStrip.tsx`
- Create: `client/src/components/MaterialCard.tsx`
- Create: `client/src/components/CncTab.tsx`
- Modify: `client/src/App.tsx`

- [ ] **Step 1: ProgressBar.tsx**

`client/src/components/ProgressBar.tsx`:
```tsx
import React from 'react';
import { ModeProgress } from '../types';

export function ProgressBar({ mode, label }: { mode: ModeProgress; label: string }) {
  const pct = mode.percentDone;
  const barColor = pct === 100 ? 'bg-green-500' : pct > 0 ? 'bg-amber-500' : 'bg-gray-700';
  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-3 mb-4">
      <div className="flex justify-between items-center mb-2">
        <span className="text-gray-200 text-sm font-semibold">{label} Progress</span>
        <span className={`text-sm font-bold ${pct === 100 ? 'text-green-400' : pct > 0 ? 'text-amber-400' : 'text-gray-500'}`}>{pct}%</span>
      </div>
      <div className="bg-gray-800 rounded-full h-2 mb-3">
        <div className={`h-full rounded-full ${barColor} transition-all`} style={{ width: `${pct}%` }} />
      </div>
      <div className="flex gap-5 text-center">
        {[['Done', mode.done, 'text-green-400'], ['Bad', mode.bad, 'text-red-400'], ['Skipped', mode.skipped, 'text-amber-400'], ['Left', mode.totalSheets - mode.done - mode.bad - mode.skipped, 'text-gray-400']].map(([label, val, color]) => (
          <div key={label as string}>
            <div className={`text-lg font-bold ${color}`}>{val}</div>
            <div className="text-gray-600 text-[9px]">{label}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: StatusChip.tsx**

`client/src/components/StatusChip.tsx`:
```tsx
import React from 'react';
import { SheetStatus } from '../types';

const styles: Record<SheetStatus, string> = {
  COMPLETE:      'bg-green-900 text-green-300',
  HAS_BAD_PARTS: 'bg-red-900 text-red-300',
  SKIPPED:       'bg-amber-900 text-amber-300',
  IN_PROGRESS:   'bg-blue-900 text-blue-300',
  NOT_STARTED:   'bg-gray-800 text-gray-500',
};
const labels: Record<SheetStatus, string> = {
  COMPLETE: '✓', HAS_BAD_PARTS: '!', SKIPPED: '⊘', IN_PROGRESS: '●', NOT_STARTED: '',
};

export function StatusChip({ status, count, label }: { status: SheetStatus; count: number; label: string }) {
  if (count === 0) return null;
  return (
    <span className={`text-[9px] px-2 py-0.5 rounded-full font-medium ${styles[status]}`}>
      {count} {label}
    </span>
  );
}

export function PageChip({ page, status, onClick }: { page: number; status: SheetStatus; onClick?: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`w-6 h-7 rounded text-[9px] font-bold flex items-center justify-content flex-col border ${styles[status]}`}
      title={`Page ${page}: ${status}`}
    >
      <span>{page}</span>
      <span className="text-[8px] leading-none">{labels[status]}</span>
    </button>
  );
}
```

- [ ] **Step 3: PageStrip.tsx**

`client/src/components/PageStrip.tsx`:
```tsx
import React from 'react';
import { PageStatus } from '../types';
import { PageChip } from './StatusChip';

interface Props { pages: PageStatus[]; onPageClick?: (page: number) => void; }

export function PageStrip({ pages, onPageClick }: Props) {
  return (
    <div className="flex gap-1 flex-wrap pt-1 pb-0.5 px-1 bg-gray-950 border-t border-gray-800">
      {pages.map(p => (
        <PageChip key={p.page} page={p.page} status={p.status} onClick={() => onPageClick?.(p.page)} />
      ))}
    </div>
  );
}
```

- [ ] **Step 4: MaterialCard.tsx**

`client/src/components/MaterialCard.tsx`:
```tsx
import React, { useState } from 'react';
import { MaterialProgress } from '../types';
import { PageStrip } from './PageStrip';
import { StatusChip } from './StatusChip';

interface Props { material: MaterialProgress; onPageClick?: (pdf: string, page: number) => void; }

const borderColor = (m: MaterialProgress) => {
  if (m.done === m.totalPages && m.totalPages > 0) return 'border-green-600';
  if (m.bad > 0) return 'border-red-700';
  if (m.done > 0) return 'border-amber-600';
  return 'border-gray-700';
};

export function MaterialCard({ material: m, onPageClick }: Props) {
  const [expanded, setExpanded] = useState(true);
  const pct = m.totalPages > 0 ? Math.round((m.done / m.totalPages) * 100) : 0;

  return (
    <div className={`bg-gray-900 border border-gray-800 border-l-2 ${borderColor(m)} rounded-lg overflow-hidden mb-3`}>
      <button onClick={() => setExpanded(e => !e)} className="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:bg-gray-800 transition-colors">
        <div className="flex-1 min-w-0">
          <div className="text-gray-100 text-xs font-semibold truncate">{m.label || m.pdfFilename}</div>
          <div className="text-gray-500 text-[10px]">{m.totalPages} sheets</div>
        </div>
        <div className="flex gap-1.5 flex-shrink-0">
          <StatusChip status="COMPLETE"      count={m.done}    label="done" />
          <StatusChip status="HAS_BAD_PARTS" count={m.bad}     label="bad" />
          <StatusChip status="SKIPPED"       count={m.skipped} label="skip" />
        </div>
        <span className={`text-xs font-bold ml-2 ${pct === 100 ? 'text-green-400' : pct > 0 ? 'text-amber-400' : 'text-gray-600'}`}>{pct}%</span>
        <span className="text-gray-600 text-xs ml-1">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && <PageStrip pages={m.pages} onPageClick={page => onPageClick?.(m.pdfFilename, page)} />}
    </div>
  );
}
```

- [ ] **Step 5: CncTab.tsx**

`client/src/components/CncTab.tsx`:
```tsx
import React from 'react';
import { JobDetail } from '../types';
import { ProgressBar } from './ProgressBar';
import { MaterialCard } from './MaterialCard';

interface Props { job: JobDetail; onOpenViewer: (pdf: string, page: number) => void; }

export function CncTab({ job, onOpenViewer }: Props) {
  return (
    <div>
      <ProgressBar mode={job.cnc} label="CNC" />
      {job.materials.length === 0
        ? <div className="text-gray-600 text-sm text-center py-8">No CNC sheets tracked yet</div>
        : job.materials.map(m => (
            <MaterialCard key={m.pdfFilename} material={m} onPageClick={(pdf, page) => onOpenViewer(pdf, page)} />
          ))
      }
    </div>
  );
}
```

- [ ] **Step 6: Wire CncTab into App.tsx**

In `client/src/App.tsx`, add import and replace the placeholder tab content:
```tsx
import { CncTab } from './components/CncTab';
// inside the tab content div:
{activeTab === 'CNC' && job && <CncTab job={job} onOpenViewer={(pdf, page) => console.log('open viewer', pdf, page)} />}
```

- [ ] **Step 7: Commit**

```bash
git add client/src && git commit -m "feat: CNC tab with progress bar, material cards, page strips"
```

---

## Task 13: Hardwoods + Assembly + Checklist Tabs + Dialogs

**Files:**
- Create: `client/src/components/HardwoodsTab.tsx`
- Create: `client/src/components/AssemblyTab.tsx`
- Create: `client/src/components/ChecklistTab.tsx`
- Create: `client/src/components/AddRipItemDialog.tsx`
- Create: `client/src/components/AddChecklistItemDialog.tsx`
- Modify: `client/src/App.tsx`

- [ ] **Step 1: HardwoodsTab.tsx**

`client/src/components/HardwoodsTab.tsx`:
```tsx
import React, { useState } from 'react';
import { JobDetail, RipItem } from '../types';
import { ProgressBar } from './ProgressBar';
import { AddRipItemDialog } from './AddRipItemDialog';

interface Props { job: JobDetail; ripItems: RipItem[]; onRipItemAdded: () => void; folderName: string; }

export function HardwoodsTab({ job, ripItems, onRipItemAdded, folderName }: Props) {
  const [showAdd, setShowAdd] = useState(false);
  return (
    <div>
      <ProgressBar mode={job.hardwoods} label="Hardwoods" />
      <div className="flex justify-between items-center mb-3">
        <h3 className="text-gray-300 text-xs font-semibold uppercase tracking-wider">Custom Rip Items</h3>
        <button onClick={() => setShowAdd(true)} className="bg-blue-700 hover:bg-blue-600 text-white text-xs px-3 py-1.5 rounded">+ Add Rip Item</button>
      </div>
      {ripItems.length === 0
        ? <div className="text-gray-600 text-sm text-center py-6 border border-dashed border-gray-800 rounded-lg">No custom rip items yet</div>
        : ripItems.map(item => (
            <div key={item.id} className="bg-gray-900 border border-gray-800 rounded-lg px-3 py-2.5 mb-2 flex items-start gap-3">
              <div className="flex-1">
                <div className="text-gray-100 text-xs font-semibold">{item.category}</div>
                <div className="text-gray-500 text-[10px]">{item.material} · {item.width}" · {item.ripsNeeded} rips{item.notes ? ` · ${item.notes}` : ''}</div>
              </div>
              <div className="text-gray-600 text-[10px]">{item.createdBy}</div>
            </div>
          ))
      }
      {showAdd && (
        <AddRipItemDialog
          folderName={folderName}
          onClose={() => setShowAdd(false)}
          onAdded={() => { onRipItemAdded(); setShowAdd(false); }}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 2: AssemblyTab.tsx**

`client/src/components/AssemblyTab.tsx`:
```tsx
import React from 'react';
import { JobDetail } from '../types';
import { ProgressBar } from './ProgressBar';
import { MaterialCard } from './MaterialCard';

interface Props { job: JobDetail; }

export function AssemblyTab({ job }: Props) {
  return (
    <div>
      <ProgressBar mode={job.assembly} label="Assembly" />
      {job.assemblyMaterials.length === 0
        ? <div className="text-gray-600 text-sm text-center py-8">No assembly materials tracked yet</div>
        : job.assemblyMaterials.map(m => <MaterialCard key={m.pdfFilename} material={m} />)
      }
    </div>
  );
}
```

- [ ] **Step 3: ChecklistTab.tsx**

`client/src/components/ChecklistTab.tsx`:
```tsx
import React, { useState } from 'react';
import { ChecklistItem } from '../types';
import { api } from '../api';
import { AddChecklistItemDialog } from './AddChecklistItemDialog';

interface Props { folderName: string; items: ChecklistItem[]; onChanged: () => void; }

export function ChecklistTab({ folderName, items, onChanged }: Props) {
  const [showAdd, setShowAdd] = useState(false);

  const toggle = async (id: string) => { await api.checklist.toggle(folderName, id); onChanged(); };
  const del    = async (id: string) => { await api.checklist.delete(folderName, id); onChanged(); };

  return (
    <div>
      <div className="flex justify-between items-center mb-4">
        <div className="text-gray-300 text-xs font-semibold uppercase tracking-wider">
          Checklist <span className="text-gray-600 font-normal">· {items.filter(i => !i.completedAt).length} pending</span>
        </div>
        <button onClick={() => setShowAdd(true)} className="bg-green-800 hover:bg-green-700 text-green-200 text-xs px-3 py-1.5 rounded">+ Add Item</button>
      </div>
      {items.length === 0
        ? <div className="text-gray-600 text-sm text-center py-8 border border-dashed border-gray-800 rounded-lg">No checklist items yet</div>
        : items.map(item => (
            <div key={item.id} className={`flex items-start gap-3 p-2.5 rounded-lg border mb-2 ${item.completedAt ? 'bg-green-950/30 border-green-900' : 'bg-gray-900 border-gray-800'}`}>
              <button onClick={() => toggle(item.id)} className={`mt-0.5 w-4 h-4 rounded border flex items-center justify-content flex-shrink-0 ${item.completedAt ? 'bg-green-700 border-green-600 text-white' : 'border-gray-600'}`}>
                {item.completedAt && <span className="text-[10px] font-bold">✓</span>}
              </button>
              <div className="flex-1 min-w-0">
                <div className={`text-xs ${item.completedAt ? 'line-through text-gray-500' : 'text-gray-100'}`}>{item.text}</div>
                {item.notes && <div className="text-[10px] text-gray-600 mt-0.5">{item.notes}</div>}
                <div className="flex gap-1.5 mt-1 flex-wrap">
                  {item.modes.map(m => (
                    <span key={m} className={`text-[9px] px-1.5 py-0.5 rounded ${item.completedAt ? 'bg-gray-800 text-gray-600' : 'bg-blue-900 text-blue-300'}`}>{m}</span>
                  ))}
                  {item.completedAt && <span className="text-[9px] text-gray-600">Done by {item.completedBy}</span>}
                </div>
              </div>
              <button onClick={() => del(item.id)} className="text-gray-700 hover:text-red-500 text-xs flex-shrink-0">✕</button>
            </div>
          ))
      }
      {showAdd && (
        <AddChecklistItemDialog
          folderName={folderName}
          onClose={() => setShowAdd(false)}
          onAdded={() => { onChanged(); setShowAdd(false); }}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 4: AddRipItemDialog.tsx**

`client/src/components/AddRipItemDialog.tsx`:
```tsx
import React, { useState } from 'react';
import { api } from '../api';

interface Props { folderName: string; onClose: () => void; onAdded: () => void; }

export function AddRipItemDialog({ folderName, onClose, onAdded }: Props) {
  const [form, setForm] = useState({ category: '', material: '', width: '', ripsNeeded: '', notes: '' });
  const [saving, setSaving] = useState(false);

  const save = async () => {
    if (!form.category || !form.material || !form.width || !form.ripsNeeded) return;
    setSaving(true);
    await api.ripItems.add(folderName, { category: form.category, material: form.material, width: form.width, ripsNeeded: Number(form.ripsNeeded), notes: form.notes });
    onAdded();
  };

  const field = (label: string, key: keyof typeof form, placeholder?: string) => (
    <div>
      <label className="block text-gray-400 text-[10px] uppercase tracking-wider mb-1">{label}</label>
      <input value={form[key]} onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
        placeholder={placeholder} className="w-full bg-gray-800 border border-gray-700 rounded px-2.5 py-1.5 text-gray-100 text-xs outline-none focus:border-blue-500" />
    </div>
  );

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-700 rounded-lg w-80 p-4" onClick={e => e.stopPropagation()}>
        <div className="flex justify-between items-center mb-4">
          <h2 className="text-white font-semibold text-sm">+ Add Rip Item</h2>
          <button onClick={onClose} className="text-gray-500 hover:text-white">✕</button>
        </div>
        <div className="flex flex-col gap-3">
          {field('Category', 'category', 'Base, Crown, Scribe...')}
          {field('Material', 'material', 'Maple, Oak...')}
          <div className="grid grid-cols-2 gap-2">
            {field('Width', 'width', '3.5"')}
            {field('Rips Needed', 'ripsNeeded', '4')}
          </div>
          {field('Notes (optional)', 'notes', 'Living room only')}
        </div>
        <div className="flex gap-2 mt-4">
          <button onClick={onClose} className="flex-1 bg-gray-700 text-gray-300 text-xs py-2 rounded">Cancel</button>
          <button onClick={save} disabled={saving} className="flex-2 bg-blue-700 hover:bg-blue-600 text-white text-xs py-2 px-4 rounded font-semibold disabled:opacity-50">
            {saving ? 'Adding...' : 'Add to Cut List'}
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: AddChecklistItemDialog.tsx**

`client/src/components/AddChecklistItemDialog.tsx`:
```tsx
import React, { useState } from 'react';
import { WorkMode } from '../types';
import { api } from '../api';

interface Props { folderName: string; onClose: () => void; onAdded: () => void; }
const ALL_MODES: WorkMode[] = ['CNC', 'HARDWOODS', 'ASSEMBLY'];

export function AddChecklistItemDialog({ folderName, onClose, onAdded }: Props) {
  const [text, setText] = useState('');
  const [modes, setModes] = useState<WorkMode[]>(['CNC']);
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);

  const toggleMode = (m: WorkMode) => setModes(ms => ms.includes(m) ? ms.filter(x => x !== m) : [...ms, m]);

  const save = async () => {
    if (!text || !modes.length) return;
    setSaving(true);
    await api.checklist.add(folderName, { text, modes, notes });
    onAdded();
  };

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-700 rounded-lg w-80 p-4" onClick={e => e.stopPropagation()}>
        <div className="flex justify-between items-center mb-4">
          <h2 className="text-white font-semibold text-sm">+ Add Checklist Item</h2>
          <button onClick={onClose} className="text-gray-500 hover:text-white">✕</button>
        </div>
        <div className="flex flex-col gap-3">
          <div>
            <label className="block text-gray-400 text-[10px] uppercase tracking-wider mb-1">Item</label>
            <textarea value={text} onChange={e => setText(e.target.value)} rows={2}
              className="w-full bg-gray-800 border border-gray-700 rounded px-2.5 py-1.5 text-gray-100 text-xs outline-none focus:border-green-500 resize-none" />
          </div>
          <div>
            <label className="block text-gray-400 text-[10px] uppercase tracking-wider mb-2">Applies to Modes</label>
            <div className="flex gap-2">
              {ALL_MODES.map(m => (
                <button key={m} onClick={() => toggleMode(m)}
                  className={`px-3 py-1.5 rounded text-xs font-medium ${modes.includes(m) ? 'bg-blue-700 text-white' : 'bg-gray-800 text-gray-500 border border-gray-700'}`}>
                  {m === 'HARDWOODS' ? 'HW' : m}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label className="block text-gray-400 text-[10px] uppercase tracking-wider mb-1">Notes (optional)</label>
            <input value={notes} onChange={e => setNotes(e.target.value)}
              className="w-full bg-gray-800 border border-gray-700 rounded px-2.5 py-1.5 text-gray-100 text-xs outline-none focus:border-green-500" />
          </div>
        </div>
        <div className="flex gap-2 mt-4">
          <button onClick={onClose} className="flex-1 bg-gray-700 text-gray-300 text-xs py-2 rounded">Cancel</button>
          <button onClick={save} disabled={saving || !text || !modes.length}
            className="flex-2 bg-green-800 hover:bg-green-700 text-green-200 text-xs py-2 px-4 rounded font-semibold disabled:opacity-50">
            {saving ? 'Adding...' : 'Add to Checklist'}
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 6: Wire all tabs into App.tsx**

Replace the tab content section in `client/src/App.tsx`:
```tsx
import { CncTab } from './components/CncTab';
import { HardwoodsTab } from './components/HardwoodsTab';
import { AssemblyTab } from './components/AssemblyTab';
import { ChecklistTab } from './components/ChecklistTab';
import { useRipItems } from './hooks/useRipItems';

// Inside App(), add:
const { items: ripItems, refresh: refreshRipItems } = useRipItems(selectedFolder);
const { items: checklist, refresh: refreshChecklist } = useChecklist(selectedFolder);

// Replace placeholder div with:
<div className="flex-1 overflow-y-auto p-4">
  {activeTab === 'CNC'       && <CncTab job={job} onOpenViewer={(pdf, page) => setViewer({ pdf, page })} />}
  {activeTab === 'HARDWOODS' && <HardwoodsTab job={job} ripItems={ripItems} folderName={job.folderName} onRipItemAdded={refreshRipItems} />}
  {activeTab === 'ASSEMBLY'  && <AssemblyTab job={job} />}
  {activeTab === 'CHECKLIST' && <ChecklistTab folderName={job.folderName} items={checklist} onChanged={refreshChecklist} />}
</div>
```

- [ ] **Step 7: Commit**

```bash
git add client/src && git commit -m "feat: hardwoods, assembly, checklist tabs + add item dialogs"
```

---

## Task 14: Sheet Viewer (PDF.js + Part Overlays)

**Files:**
- Create: `client/src/viewer/SheetViewer.tsx`
- Create: `client/src/viewer/PdfCanvas.tsx`
- Create: `client/src/viewer/PartOverlay.tsx`
- Create: `client/src/viewer/PartTable.tsx`
- Create: `client/src/viewer/PageThumbnailStrip.tsx`
- Modify: `client/src/App.tsx`

- [ ] **Step 1: PdfCanvas.tsx** (PDF.js page renderer)

`client/src/viewer/PdfCanvas.tsx`:
```tsx
import React, { useEffect, useRef, useState } from 'react';
import * as pdfjsLib from 'pdfjs-dist';

// Point worker at bundled worker file
pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString();

interface Props { pdfUrl: string; page: number; scale?: number; onPageCount?: (n: number) => void; }

export function PdfCanvas({ pdfUrl, page, scale = 1.5, onPageCount }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [dims, setDims] = useState({ width: 0, height: 0 });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const pdf = await pdfjsLib.getDocument(pdfUrl).promise;
      onPageCount?.(pdf.numPages);
      if (cancelled) return;
      const pg = await pdf.getPage(page);
      const vp = pg.getViewport({ scale });
      const canvas = canvasRef.current!;
      canvas.width = vp.width; canvas.height = vp.height;
      setDims({ width: vp.width, height: vp.height });
      await pg.render({ canvasContext: canvas.getContext('2d')!, viewport: vp }).promise;
    })();
    return () => { cancelled = true; };
  }, [pdfUrl, page, scale]);

  return <canvas ref={canvasRef} style={{ display: 'block' }} />;
}
```

- [ ] **Step 2: PartOverlay.tsx** (SVG boxes over page)

`client/src/viewer/PartOverlay.tsx`:
```tsx
import React from 'react';
import { PageMetadata, PageStatus, OcrBox } from '../types';

interface Props {
  pageMeta: PageMetadata | null;
  pageStatus: PageStatus | null;
  dims: { width: number; height: number };
  pdfDims: { width: number; height: number };
  scale: number;
}

function boxColor(partNum: number, badParts: number[]): string {
  if (badParts.includes(partNum)) return '#ef4444';
  return '#3b82f6';
}

export function PartOverlay({ pageMeta, pageStatus, dims, scale }: Props) {
  if (!pageMeta) return null;
  const bad = pageStatus?.badParts ?? [];

  return (
    <svg style={{ position: 'absolute', top: 0, left: 0, width: dims.width, height: dims.height, pointerEvents: 'none' }}>
      {pageMeta.parts.map(part => {
        const boxes: OcrBox[] = pageMeta.ocrBoxes?.[String(part.number)] ?? [];
        return boxes.map((box, i) => {
          const x = box.left * scale, y = box.top * scale;
          const w = (box.right - box.left) * scale, h = (box.bottom - box.top) * scale;
          const color = boxColor(part.number, bad);
          return (
            <g key={`${part.number}-${i}`}>
              <rect x={x} y={y} width={w} height={h} fill={`${color}22`} stroke={color} strokeWidth={1.5} />
              <text x={x + 3} y={y + 10} fill={color} fontSize={8} fontWeight="bold">P{part.number}</text>
            </g>
          );
        });
      })}
    </svg>
  );
}
```

- [ ] **Step 3: PartTable.tsx** (right panel)

`client/src/viewer/PartTable.tsx`:
```tsx
import React from 'react';
import { PageMetadata, PageStatus } from '../types';

interface Props { pageMeta: PageMetadata | null; pageStatus: PageStatus | null; }

export function PartTable({ pageMeta, pageStatus }: Props) {
  const bad = pageStatus?.badParts ?? [];
  const parts = pageMeta?.parts ?? [];

  return (
    <div className="flex flex-col h-full">
      <div className="px-3 py-2 border-b border-gray-800">
        <div className="text-gray-200 text-xs font-semibold">Parts — Sheet {pageMeta ? '' : '?'}</div>
        <div className="flex gap-1.5 mt-1 flex-wrap">
          {pageStatus && (
            <>
              <span className="text-[9px] text-green-400">{parts.filter(p => !bad.includes(p.number)).length} ok</span>
              {bad.length > 0 && <span className="text-[9px] text-red-400">{bad.length} bad</span>}
            </>
          )}
        </div>
      </div>
      <div className="flex text-[9px] text-gray-600 uppercase px-2.5 py-1 border-b border-gray-800">
        <span className="w-6">#</span><span className="flex-1">Name / Dim</span><span className="w-10 text-right">Status</span>
      </div>
      <div className="flex-1 overflow-y-auto">
        {parts.length === 0
          ? <div className="text-gray-600 text-xs text-center py-6">No part data</div>
          : parts.map(p => {
              const isBad = bad.includes(p.number);
              const dim = p.width && p.length ? `${p.width}" × ${p.length}"` : '';
              return (
                <div key={p.number} className={`flex items-center px-2.5 py-1.5 border-b border-gray-900 border-l-2 ${isBad ? 'border-l-red-500 bg-red-950/20' : 'border-l-gray-700'}`}>
                  <span className="w-6 text-gray-500 text-[9px]">P{p.number}</span>
                  <div className="flex-1 min-w-0">
                    <div className={`text-[10px] truncate ${isBad ? 'text-red-300' : 'text-gray-200'}`}>{p.name}</div>
                    {dim && <div className="text-[9px] text-gray-600">{dim}</div>}
                  </div>
                  <span className={`w-10 text-right text-[10px] ${isBad ? 'text-red-400' : 'text-gray-600'}`}>{isBad ? '⚠ BAD' : '—'}</span>
                </div>
              );
            })
        }
      </div>
    </div>
  );
}
```

- [ ] **Step 4: PageThumbnailStrip.tsx**

`client/src/viewer/PageThumbnailStrip.tsx`:
```tsx
import React from 'react';
import { PageStatus, SheetStatus } from '../types';

const chipColor: Record<SheetStatus, string> = {
  COMPLETE:      'bg-green-600 text-black',
  HAS_BAD_PARTS: 'bg-red-800 border border-red-500 text-red-200',
  SKIPPED:       'bg-amber-800 text-amber-200',
  IN_PROGRESS:   'bg-blue-700 text-white',
  NOT_STARTED:   'bg-gray-800 text-gray-500',
};

interface Props { pages: PageStatus[]; currentPage: number; onSelect: (page: number) => void; }

export function PageThumbnailStrip({ pages, currentPage, onSelect }: Props) {
  return (
    <div className="flex gap-1.5 overflow-x-auto p-2 bg-gray-950 border-t border-gray-800">
      {pages.map(p => (
        <button
          key={p.page}
          onClick={() => onSelect(p.page)}
          className={`flex-shrink-0 w-7 h-8 rounded text-[9px] font-bold flex flex-col items-center justify-center ${chipColor[p.status]} ${p.page === currentPage ? 'ring-2 ring-white' : ''}`}
        >
          {p.page}
        </button>
      ))}
      {pages.length === 0 && (
        <div className="flex gap-1.5">
          {Array.from({ length: 8 }).map((_, i) => (
            <button key={i} onClick={() => onSelect(i + 1)} className={`flex-shrink-0 w-7 h-8 rounded text-[9px] font-bold ${chipColor.NOT_STARTED} ${i + 1 === currentPage ? 'ring-2 ring-white' : ''}`}>{i + 1}</button>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: SheetViewer.tsx** (split layout shell)

`client/src/viewer/SheetViewer.tsx`:
```tsx
import React, { useState, useEffect } from 'react';
import { PdfCanvas } from './PdfCanvas';
import { PartOverlay } from './PartOverlay';
import { PartTable } from './PartTable';
import { PageThumbnailStrip } from './PageThumbnailStrip';
import { PageMetadata, PageStatus, PdfMetadata } from '../types';
import { api } from '../api';

interface Props {
  folderName: string;
  pdfFilename: string;
  initialPage: number;
  pages: PageStatus[];       // from progressReader
  pdfMeta: PdfMetadata | null;
  onClose: () => void;
  onAction: (file: string, page: number, action: 'complete' | 'skip') => Promise<void>;
}

export function SheetViewer({ folderName, pdfFilename, initialPage, pages, pdfMeta, onClose, onAction }: Props) {
  const [page, setPage] = useState(initialPage);
  const [pageCount, setPageCount] = useState(0);
  const [dims, setDims] = useState({ width: 0, height: 0 });
  const [saving, setSaving] = useState(false);

  const pdfUrl = `/api/pdf/${folderName}/${pdfFilename}`;
  const pageMeta: PageMetadata | null = pdfMeta?.pages.find(p => p.pageNumber === page) ?? null;
  const pageStatus: PageStatus | null = pages.find(p => p.page === page) ?? null;
  const SCALE = 1.5;

  const act = async (action: 'complete' | 'skip') => {
    setSaving(true);
    await onAction(pdfFilename, page, action);
    setSaving(false);
  };

  return (
    <div className="fixed inset-0 bg-gray-950 z-40 flex flex-col">
      {/* Toolbar */}
      <div className="flex items-center gap-3 px-4 py-2 bg-gray-900 border-b border-gray-800 flex-shrink-0">
        <button onClick={onClose} className="text-gray-400 hover:text-white text-sm">← Back</button>
        <span className="text-gray-500">·</span>
        <span className="text-gray-300 text-sm">{pdfFilename}</span>
        <span className="text-gray-500">·</span>
        <span className="text-blue-300 text-sm font-semibold">Page {page} / {pageCount || '?'}</span>
        <div className="ml-auto flex gap-2">
          <button onClick={() => act('complete')} disabled={saving}
            className="bg-green-800 hover:bg-green-700 text-green-200 text-xs px-3 py-1.5 rounded font-semibold disabled:opacity-50">
            ✓ Mark Done
          </button>
          <button onClick={() => act('skip')} disabled={saving}
            className="bg-red-900 hover:bg-red-800 text-red-300 text-xs px-3 py-1.5 rounded disabled:opacity-50">
            ⊘ Skip Sheet
          </button>
          <div className="w-px h-5 bg-gray-700 self-center" />
          <button onClick={() => setPage(p => Math.max(1, p - 1))} className="bg-gray-800 text-gray-400 px-2 py-1.5 rounded text-xs">◀</button>
          <button onClick={() => setPage(p => Math.min(pageCount || 999, p + 1))} className="bg-gray-800 text-gray-400 px-2 py-1.5 rounded text-xs">▶</button>
        </div>
      </div>

      {/* Split: PDF left, parts right */}
      <div className="flex flex-1 min-h-0">
        {/* PDF pane */}
        <div className="flex-1 overflow-auto bg-gray-900 flex items-start justify-center p-4 relative">
          <div className="relative inline-block">
            <PdfCanvas pdfUrl={pdfUrl} page={page} scale={SCALE} onPageCount={n => { setPageCount(n); }} />
            <PartOverlay pageMeta={pageMeta} pageStatus={pageStatus} dims={dims} pdfDims={dims} scale={SCALE} />
          </div>
        </div>
        {/* Parts panel */}
        <div className="w-56 flex-shrink-0 border-l border-gray-800 bg-gray-900 flex flex-col">
          <PartTable pageMeta={pageMeta} pageStatus={pageStatus} />
        </div>
      </div>

      {/* Page thumbnail strip */}
      <PageThumbnailStrip pages={pages} currentPage={page} onSelect={setPage} />
    </div>
  );
}
```

- [ ] **Step 6: Wire viewer into App.tsx**

Add state and handler in `App.tsx`:
```tsx
import { SheetViewer } from './viewer/SheetViewer';

// State:
const [viewer, setViewer] = useState<{ pdf: string; page: number } | null>(null);

// Handler:
const handleTrackerAction = async (file: string, page: number, action: 'complete' | 'skip') => {
  if (!selectedFolder) return;
  await api.tracker.post(selectedFolder, { file, page, action });
  refreshJob(); // call job refresh after action
};

// In JSX, before closing </div>:
{viewer && job && (
  <SheetViewer
    folderName={job.folderName}
    pdfFilename={viewer.pdf}
    initialPage={viewer.page}
    pages={job.materials.find(m => m.pdfFilename === viewer.pdf)?.pages ?? []}
    pdfMeta={null}
    onClose={() => setViewer(null)}
    onAction={handleTrackerAction}
  />
)}
```

Pass `onOpenViewer={(pdf, page) => setViewer({ pdf, page })}` to `CncTab`.

- [ ] **Step 7: Verify viewer opens**

Click any page chip in the CNC tab — viewer should open, PDF should render, Done/Skip buttons should work and update tracker files.

- [ ] **Step 8: Commit**

```bash
git add client/src/viewer && git commit -m "feat: PDF.js sheet viewer with part overlays and tracker actions"
```

---

## Task 15: Build + Final Verification

- [ ] **Step 1: Run all server tests**

```bash
cd "C:\Scripts\kkc-admin\server" && npx jest --no-coverage
```
Expected: all tests PASS (jobScanner, progressReader, adminStore, trackerWriter)

- [ ] **Step 2: Build client and serve via Express**

```bash
cd "C:\Scripts\kkc-admin" && npm run build && npm start
```
Expected: `KKC Admin  http://localhost:3000  [admin-YOURPC]`

- [ ] **Step 3: Smoke test the live app**

Visit `http://localhost:3000`:
- [ ] Jobs appear in sidebar with progress bars
- [ ] Clicking a job shows mode tabs and material cards
- [ ] Page chips show correct colors
- [ ] Add Rip Item dialog saves to `.metadata/admin/rip_items.json`
- [ ] Add Checklist Item dialog saves to `.metadata/admin/checklist.json`
- [ ] Clicking a page chip opens the PDF viewer
- [ ] PDF renders in left panel
- [ ] Mark Done / Skip Sheet writes to `CNC/.tracker/admin-{hostname}.json`
- [ ] Refreshing the page shows updated progress

- [ ] **Step 4: Final commit**

```bash
git add -A && git commit -m "feat: kkc-admin Phase 1 complete"
```

---

## Self-Review Notes

**Spec coverage check:**
- ✅ Project scaffold (monorepo, Express + React/Vite, Tailwind, config) — Task 1
- ✅ Job scanner (deployment_gate.json, cabinet index) — Task 3
- ✅ Progress reader (multi-device tracker merge) — Task 4
- ✅ Hardwoods progress — Task 5
- ✅ PDF metadata reader (parts + OCR boxes) — Task 6
- ✅ Admin store (rip items + checklist CRUD + `.metadata/admin/`) — Task 7
- ✅ Tracker writer (writes to `CNC/.tracker/admin-{hostname}.json`) — Task 8
- ✅ REST API (all 11 endpoints) — Task 9
- ✅ Device ID via `os.hostname()` — Task 2 + Task 8/9
- ✅ Sidebar with 3-mode mini bars — Task 11
- ✅ CNC tab: progress bar + material cards + page strips — Task 12
- ✅ Hardwoods tab + Add Rip Item dialog — Task 13
- ✅ Assembly tab — Task 13
- ✅ Checklist tab + Add Checklist Item dialog — Task 13
- ✅ PDF.js viewer with part overlays — Task 14
- ✅ Skip/complete/bad_part tracker actions — Task 14
- ✅ `.metadata/admin/` namespace (no RJW conflict) — Task 7
- ✅ `config.json` with basePath + port — Task 1/2

