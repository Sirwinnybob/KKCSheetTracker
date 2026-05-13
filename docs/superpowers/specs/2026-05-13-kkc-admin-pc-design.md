# KKC Admin PC Dashboard — Design Spec
**Date:** 2026-05-13  
**Status:** Approved for implementation

---

## Overview

A TypeScript web app (Node.js + React) that acts as a PC-based admin dashboard for KKCSheetTracker. It reads the same file-based data the Android tablets use, writes tracker actions in the same format, and adds admin-only capabilities not available on the tablets — primarily custom rip items and job checklists.

**Primary use cases:**
1. See progress at a glance across all jobs and all modes
2. Add custom rip items (base, crown, scribe, etc.) to hardwoods cut lists
3. Add and manage job checklists

**Secondary use cases:**
- View PDF sheets with part overlays (same viewer feel as the Android app)
- Skip sheets, mark complete, flag bad parts from the PC
- View hardwoods and assembly progress

---

## Architecture

### Stack
| Layer | Choice |
|-------|--------|
| Backend | Node.js + TypeScript + Express |
| Frontend | React + Vite + TypeScript |
| PDF viewer | PDF.js (`pdfjs-dist`) |
| Styling | Tailwind CSS (dark theme) |
| Monorepo | Single repo, `server/` + `client/` packages |

### Project Layout
```
kkc-admin/
  server/
    src/
      index.ts          ← Express entry point
      config.ts         ← loads config.json
      routes/
        jobs.ts         ← job scanning + progress API
        pdf.ts          ← PDF file serving
        tracker.ts      ← read/write tracker actions
        admin.ts        ← rip items + checklist CRUD
      lib/
        jobScanner.ts   ← scans basePath for job folders
        progressReader.ts ← merges tracker files across devices
        trackerWriter.ts  ← appends actions to admin tracker file
    package.json
  client/
    src/
      App.tsx
      components/
      pages/
    package.json
  config.json           ← basePath, port (deviceId auto-detected)
  package.json          ← root: "npm start" boots everything
```

### Deployment
- `npm install` at repo root installs both packages
- `npm run build` compiles client → `server/public/`
- `npm start` boots Express, serves API + built frontend on one port
- `npm run dev` runs both with hot reload (concurrently)
- Later: drop the whole folder into `C:\Scripts\Hours Tracker` and import the Express router

---

## Configuration

`config.json` (sits next to `package.json`, gitignored for secrets):
```json
{
  "basePath": "\\\\server\\JobFiles",
  "port": 3000
}
```

**Device ID** is auto-detected at startup: `admin-${os.hostname()}` (e.g. `admin-WORKSTATION`, `admin-SHOPPC`). No manual config needed — each PC gets a unique tracker file automatically, preventing conflicts with other PCs and the Hours Tracker server.

---

## Data Access

### Reading jobs
The server scans `basePath` directories. A folder is a job if it contains a `.metadata/deployment_gate.json`. Job metadata (name, number, materials, mode) is read from:
- `.metadata/deployment_gate.json` — job number, name, hidden flag
- `.metadata/cabinet_sheet_index.json` — assembly/delivery info
- `.metadata/hardwoods/cutlist_index.json` — hardwoods rows

### Reading progress
For each job, all `*.json` files in `CNC/.tracker/` and `.metadata/hardwoods/.tracker/` are read and merged (same multi-device logic as the Android app). The merged state drives all progress numbers.

### Writing tracker actions
The PC writes to its own tracker file: `CNC/.tracker/admin-{hostname}.json` using the same `TrackerAction` format the tablets use:
```json
{ "file": "Cabinet.pdf", "page": 3, "action": "complete", "timestamp": "...", "fileFingerprint": "..." }
```
This means PC actions appear on tablets and vice versa via Syncthing.

### PDF serving
PDFs are served as static files: `GET /api/pdf/:jobFolder/:filename` streams the file from `basePath`. The client loads pages via PDF.js.

---

## Admin Data (New Files — No RJW Conflict)

All admin-created data lives under `<job>/.metadata/admin/` — a subdirectory that the Ready Jobs Watcher does not touch.

### Custom Rip Items — `.metadata/admin/rip_items.json`
```json
{
  "schemaVersion": 1,
  "items": [
    {
      "id": "uuid-v4",
      "category": "Base Moulding",
      "material": "Maple",
      "width": "3.5",
      "ripsNeeded": 4,
      "notes": "Living room only",
      "createdAt": "ISO-8601",
      "createdBy": "admin-WORKSTATION"
    }
  ]
}
```
These appear in the Hardwoods rip cut list under a "Custom Items" group. The Android app will read this file (phase 2 parity work).

### Checklist — `.metadata/admin/checklist.json`
```json
{
  "schemaVersion": 1,
  "items": [
    {
      "id": "uuid-v4",
      "text": "Verify door swing direction with customer",
      "modes": ["CNC", "ASSEMBLY"],
      "notes": "Check drawings page 3",
      "createdAt": "ISO-8601",
      "createdBy": "admin-WORKSTATION",
      "completedAt": null,
      "completedBy": null
    }
  ]
}
```
Completion is shared — `completedAt`/`completedBy` are written by whichever device checks the item. Any device marking it done = done for all. The Android app will read this file (phase 2 parity work).

---

## UI Layout

### Shell (Layout C — approved)
```
┌─────────────────────────────────────────────────┐
│  KKC Admin          [search jobs...]            │
├──────────────┬──────────────────────────────────┤
│ Mode         │  #1042 — Smith Kitchen   + Add ▾ │
│  ● CNC       ├──────────────────────────────────┤
│  Hardwoods   │  CNC │ Hardwoods │ Assembly │ ☑  │
│  Assembly    ├──────────────────────────────────┤
├──────────────│  [progress + material cards]     │
│ #1042 Smith  │                                  │
│ CNC ████░░   │                                  │
│ HW  ██░░░░   │                                  │
│ ASM ░░░░░░   │                                  │
│              │                                  │
│ #1039 Jones  │                                  │
│ ...          │                                  │
└──────────────┴──────────────────────────────────┘
```

**Sidebar:** Job list with 3 mini progress bars (CNC / HW / ASM) per job. Search filter at top. Clicking a job loads it in the main panel.

**Main panel — mode tabs:**
- **CNC** — overall progress bar + stat counts (done/bad/skip/left) + material cards
- **Hardwoods** — rip cut list progress + custom items section
- **Assembly** — assembly PDF progress
- **Checklist (☑)** — item list with mode tags; badge shows pending count

**Material cards:** Color-coded left border (green=complete, amber=in-progress, gray=not started). Shows sheet count + status chips + expandable page strip (each page as a colored chip). Click card → opens sheet viewer.

### Sheet Viewer (split layout — mirrors Android AdaptiveSplitLayout)
```
┌──── toolbar: ← Cabinet.pdf · Page 3/8 · [✓ Done] [⊘ Skip] [◀][▶] ────┐
│                                          │  Parts — Sheet 3            │
│                                          │  2 done · 1 bad · 1 skip    │
│         PDF.js page bitmap               ├─────────────────────────────│
│         with part overlays               │  P1  Door Panel  14¾×28½  ✓ │
│         (color coded: blue=pending,      │  P2  Side Panel  22×34¼   — │
│          green=done, red=bad,            │  P3  Top Rail ⚠  3½×16   BAD│
│          dashed=skipped)                 │  P4  Bottom      22×15¾   ✓ │
│                                          │  P5  Shelf       21¼×15¾  — │
├── page thumbnail strip ──────────────────┴─────────────────────────────┤
│  [1✓][2✓][3●][4 ][5!][6 ][7 ][8 ]                                     │
└────────────────────────────────────────────────────────────────────────┘
```

### Add Rip Item Dialog
Fields: Category name, Material, Width, Rips Needed, Notes (optional).  
Writes to `.metadata/admin/rip_items.json`.

### Add Checklist Item Dialog
Fields: Item text, Modes (multi-select toggle: CNC / Hardwoods / Assembly), Notes (optional).  
Writes to `.metadata/admin/checklist.json`.

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/jobs` | List all jobs with merged progress summary |
| `GET` | `/api/jobs/:folder` | Single job detail (materials, progress, metadata) |
| `GET` | `/api/jobs/:folder/checklist` | Checklist items for job |
| `POST` | `/api/jobs/:folder/checklist` | Add checklist item |
| `PATCH` | `/api/jobs/:folder/checklist/:id` | Toggle complete |
| `DELETE` | `/api/jobs/:folder/checklist/:id` | Remove checklist item |
| `GET` | `/api/jobs/:folder/rip-items` | Custom rip items for job |
| `POST` | `/api/jobs/:folder/rip-items` | Add custom rip item |
| `DELETE` | `/api/jobs/:folder/rip-items/:id` | Remove custom rip item |
| `POST` | `/api/jobs/:folder/tracker` | Write a tracker action (complete/skip/bad_part) |
| `GET` | `/api/pdf/:folder/:filename` | Stream PDF file |

---

## Phase Plan

### Phase 1 — Basic Admin App (this build)
- [ ] Project scaffold (monorepo, Express + React/Vite, Tailwind, config)
- [ ] Job scanner — reads deployment_gate.json, enumerates job folders
- [ ] Progress reader — merges all tracker JSON files per job
- [ ] Sidebar with job list + 3-mode mini progress bars
- [ ] CNC progress tab — overall bar + material cards + page strips
- [ ] Hardwoods progress tab — rip cut list summary + custom items section
- [ ] Assembly progress tab — basic material progress
- [ ] Checklist tab — list + check off + add item dialog
- [ ] Add Rip Item dialog — writes to `.metadata/admin/rip_items.json`
- [ ] PDF.js sheet viewer — split layout with part overlays
- [ ] Tracker write actions — skip sheet, mark complete, flag bad part

### Phase 2 — Android App Parity
- [ ] Android: read `.metadata/admin/rip_items.json`, show in hardwoods rip cut list under "Custom Items"
- [ ] Android: read `.metadata/admin/checklist.json`, show Checklist tab in job detail and hardwoods workspace
- [ ] Android: allow checking off checklist items from tablets
- [ ] PC: Hardwoods full cut list view (secondary, detailed row view)

---

## Conflict Avoidance Summary

| Data | Path | Writer | Reader |
|------|------|--------|--------|
| Tracker actions | `CNC/.tracker/admin-{hostname}.json` | Admin PC | Tablets, Admin PC, RJW (reads only) |
| Custom rip items | `.metadata/admin/rip_items.json` | Admin PC | Tablets (phase 2) |
| Checklist | `.metadata/admin/checklist.json` | Admin PC + tablets | All devices |
| Cabinet index | `.metadata/cabinet_sheet_index.json` | RJW only | Admin PC (reads) |
| Hardwoods index | `.metadata/hardwoods/cutlist_index.json` | RJW only | Admin PC (reads) |
| Deployment gate | `.metadata/deployment_gate.json` | RJW only | Admin PC (reads) |
