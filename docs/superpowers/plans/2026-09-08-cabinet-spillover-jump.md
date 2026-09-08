# Cabinet Spillover Jump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a tablet user jumps/searches to a cabinet whose only page is a spillover page (cabinet numbers/BOM rows, no drawing), navigate them to the wall's/cabinet's actual drawing page instead.

**Architecture:** The backend indexer (Hours Tracker's `ready_jobs_worker_core`, the live writer of `cabinet_sheet_index.json`) computes a per-page "drawing signal" (`len(page.get_drawings()) + len(page.get_images())`, already has the page object open) and, within each room/wall group (Plans & Elevations) or per-cabinet group (Assembly Sheets), resolves every page to the page with the highest signal. That resolution — `hasDrawing`/`drawingPage` — is written into `pageDetails` in the published JSON. The Android tablet does zero PDF processing: `resolveJumpPage()` just reads `drawingPage` off the page it would otherwise land on.

**Tech Stack:** Backend: Python, PyMuPDF (`fitz`), pytest. Tablet: Kotlin, Jetpack Compose, Gson, JUnit4.

**Design doc:** `docs/superpowers/specs/2026-09-08-cabinet-spillover-jump-design.md`

**Repos touched:**
- `C:\Scripts\Hours Tracker\backend` — indexer changes (Tasks 1-4)
- `C:\Scripts\KKCSheetTracker` — tablet changes (Tasks 5-7)

Hours Tracker backend has its own venv at `C:\Scripts\Hours Tracker\backend\venv`. All backend test commands below assume `cd "C:\Scripts\Hours Tracker\backend"` first and use `venv\Scripts\python.exe -m pytest`.

---

### Task 1: Pure drawing-resolution module

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\cabinet_drawing_resolver.py`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_cabinet_drawing_resolver.py`

This module is pure — plain ints and dicts in, no `fitz`/filesystem — mirroring `cabinet_reference_parser.py`'s "pure parsing helpers" contract.

- [ ] **Step 1: Write the failing tests**

```python
from __future__ import annotations

from ready_jobs_worker_core.cabinet_drawing_resolver import (
    apply_drawing_resolution,
    group_pages_by_room_wall,
    resolve_drawing_pages,
)


def test_resolve_drawing_pages_picks_max_signal_within_group() -> None:
    resolved = resolve_drawing_pages(groups=[[5, 6]], page_signals={5: 7832, 6: 189})
    assert resolved == {5: 5, 6: 5}


def test_resolve_drawing_pages_breaks_ties_by_earliest_page() -> None:
    resolved = resolve_drawing_pages(groups=[[3, 1, 2]], page_signals={1: 5, 2: 5, 3: 5})
    assert resolved == {1: 1, 2: 1, 3: 1}


def test_resolve_drawing_pages_treats_missing_signal_as_zero() -> None:
    resolved = resolve_drawing_pages(groups=[[1, 2]], page_signals={1: 10})
    assert resolved == {1: 1, 2: 1}


def test_resolve_drawing_pages_handles_multiple_independent_groups() -> None:
    resolved = resolve_drawing_pages(
        groups=[[1, 2], [3, 4, 5]],
        page_signals={1: 100, 2: 5, 3: 1, 4: 1, 5: 50},
    )
    assert resolved == {1: 1, 2: 1, 3: 5, 4: 5, 5: 5}


def test_resolve_drawing_pages_ignores_empty_groups() -> None:
    resolved = resolve_drawing_pages(groups=[[], [1]], page_signals={1: 1})
    assert resolved == {1: 1}


def test_group_pages_by_room_wall_splits_on_context_change() -> None:
    page_details = {
        "5": {"room": "Room #1", "wall": "Wall #5"},
        "6": {"room": "Room #1", "wall": "Wall #5"},
        "7": {"room": "Room #1", "wall": "Wall #6"},
    }
    assert group_pages_by_room_wall(page_details) == [[5, 6], [7]]


def test_group_pages_by_room_wall_skips_pages_with_no_details() -> None:
    # Cabinet-0 appliance placeholders never get a pageDetails entry at all.
    page_details = {
        "5": {"room": "Room #1", "wall": "Wall #5"},
        "7": {"room": "Room #1", "wall": "Wall #6"},
    }
    assert group_pages_by_room_wall(page_details) == [[5], [7]]


def test_group_pages_by_room_wall_treats_missing_room_or_wall_as_its_own_group() -> None:
    page_details = {
        "1": {"room": None, "wall": None},
        "2": {"room": None, "wall": None},
        "3": {"room": "Room #1", "wall": "Wall #1"},
    }
    assert group_pages_by_room_wall(page_details) == [[1, 2], [3]]


def test_apply_drawing_resolution_mutates_page_details_in_place() -> None:
    page_details = {
        "5": {"cabinets": ["5"], "room": "Room #1", "wall": "Wall #5"},
        "6": {"cabinets": ["12"], "room": "Room #1", "wall": "Wall #5"},
    }
    apply_drawing_resolution(page_details, groups=[[5, 6]], page_signals={5: 7832, 6: 189})
    assert page_details["5"]["drawingPage"] == 5
    assert page_details["5"]["hasDrawing"] is True
    assert page_details["6"]["drawingPage"] == 5
    assert page_details["6"]["hasDrawing"] is False


def test_apply_drawing_resolution_ignores_group_pages_missing_from_page_details() -> None:
    page_details = {"5": {"cabinets": ["5"], "room": "Room #1", "wall": "Wall #5"}}
    apply_drawing_resolution(page_details, groups=[[5, 6]], page_signals={5: 10, 6: 1})
    assert page_details["5"]["drawingPage"] == 5
    assert "6" not in page_details
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_drawing_resolver.py -v`
Expected: FAIL / ERROR with `ModuleNotFoundError: No module named 'ready_jobs_worker_core.cabinet_drawing_resolver'`

- [ ] **Step 3: Write the implementation**

```python
"""Resolve which page holds a cabinet's or wall's actual drawing.

Pure resolution logic: takes per-page "drawing signal" scores (computed by
the adapter from PyMuPDF, kept out of this module deliberately) plus page
groupings, and decides which page each page's cabinets should navigate to
when jumped to. A page whose own signal isn't the group's max is a
"spillover" page (cabinet numbers / BOM rows, no picture) -- this module
tells the caller which page in its group actually has the picture.
"""

from __future__ import annotations

from collections.abc import Mapping, MutableMapping, Sequence


def resolve_drawing_pages(
    groups: Sequence[Sequence[int]],
    page_signals: Mapping[int, int],
) -> dict[int, int]:
    """Return {page_number: drawing_page_number} for every page in every group.

    Within each group, the page with the highest signal wins (ties broken by
    the lowest page number); every page in the group, including the winner,
    maps to that winning page number. A page missing from ``page_signals`` is
    treated as signal 0. Empty groups contribute nothing.
    """
    resolved: dict[int, int] = {}
    for group in groups:
        if not group:
            continue
        winner = min(group, key=lambda page: (-page_signals.get(page, 0), page))
        for page in group:
            resolved[page] = winner
    return resolved


def group_pages_by_room_wall(
    page_details: Mapping[str, Mapping[str, object]],
) -> list[list[int]]:
    """Group consecutive pages that share the same (room, wall) context.

    Only pages present in ``page_details`` participate -- a page with no
    cabinets (e.g. a filtered cabinet-0 appliance placeholder) never starts
    or extends a group. Pages are processed in ascending page-number order,
    so a run only merges pages that are both adjacent in that order and
    share the same (room, wall) pair, including a shared ``(None, None)``.
    """
    pages = sorted(int(key) for key in page_details)
    groups: list[list[int]] = []
    current: list[int] = []
    previous_key: tuple[object, object] | None = None
    for page in pages:
        detail = page_details[str(page)]
        key = (detail.get("room"), detail.get("wall"))
        if current and key == previous_key:
            current.append(page)
        else:
            if current:
                groups.append(current)
            current = [page]
        previous_key = key
    if current:
        groups.append(current)
    return groups


def apply_drawing_resolution(
    page_details: MutableMapping[str, MutableMapping[str, object]],
    groups: Sequence[Sequence[int]],
    page_signals: Mapping[int, int],
) -> None:
    """Set ``hasDrawing``/``drawingPage`` on every page-detail entry in ``groups``.

    Mutates ``page_details`` in place. A page number present in a group but
    absent from ``page_details`` is skipped -- there's nothing to annotate.
    """
    resolved = resolve_drawing_pages(groups, page_signals)
    for page, drawing_page in resolved.items():
        detail = page_details.get(str(page))
        if detail is None:
            continue
        detail["drawingPage"] = drawing_page
        detail["hasDrawing"] = drawing_page == page


__all__ = [
    "apply_drawing_resolution",
    "group_pages_by_room_wall",
    "resolve_drawing_pages",
]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_drawing_resolver.py -v`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/cabinet_drawing_resolver.py backend/tests/test_ready_jobs_worker_core_cabinet_drawing_resolver.py
git commit -m "feat: add pure cabinet drawing-page resolver

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Extend PDF-reading adapter with a per-page drawing signal

**Files:**
- Modify: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\cabinet_reference_reader.py:138-174` (the `read_pdf_page_texts` function)
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_cabinet_reference_reader.py`

`_FakePage`/`_FakeDocument` in the test file are shared by ~20 existing tests. Extend `_FakePage` with optional `drawings`/`images` counts (default 0) so every existing test keeps passing unchanged, then add `read_pdf_pages()`.

- [ ] **Step 1: Extend the fake page fixture and write the failing tests**

In `test_ready_jobs_worker_core_cabinet_reference_reader.py`, replace the `_FakePage` class (lines 21-29):

```python
class _FakePage:
    def __init__(
        self,
        text: str,
        *,
        error: Exception | None = None,
        drawings: int = 0,
        images: int = 0,
    ) -> None:
        self.text = text
        self.error = error
        self._drawings = [object()] * drawings
        self._images = [object()] * images

    def get_text(self) -> str:
        if self.error is not None:
            raise self.error
        return self.text

    def get_drawings(self):
        return self._drawings

    def get_images(self):
        return self._images
```

Add these tests near `test_read_pdf_page_texts_extracts_all_pages_and_closes_document` (import `read_pdf_pages` alongside the existing `read_pdf_page_texts` import at the top of the file):

```python
def test_read_pdf_pages_extracts_text_and_drawing_signal(tmp_path: Path) -> None:
    path = _touch(tmp_path / "Plans.pdf")
    document = _FakeDocument(
        (
            _FakePage("page one", drawings=5, images=1),
            _FakePage("page two", drawings=0, images=0),
        )
    )

    texts, signals = read_pdf_pages(path, opener=lambda opened_path: document)

    assert texts == ("page one", "page two")
    assert signals == (6, 0)
    assert document.closed is True


def test_read_pdf_pages_closes_document_when_extraction_fails(tmp_path: Path) -> None:
    path = _touch(tmp_path / "Plans.pdf")
    document = _FakeDocument((_FakePage("ok"), _FakePage("bad", error=ValueError("broken page"))))

    with pytest.raises(CabinetIndexBuildError) as raised:
        read_pdf_pages(path, opener=lambda opened_path: document)

    assert document.closed is True
    assert raised.value.stage == "extraction"


def test_read_pdf_page_texts_still_returns_text_only(tmp_path: Path) -> None:
    path = _touch(tmp_path / "Plans.pdf")
    document = _FakeDocument((_FakePage("page one", drawings=5), _FakePage("page two")))

    pages = read_pdf_page_texts(path, opener=lambda opened_path: document)

    assert pages == ("page one", "page two")
```

- [ ] **Step 2: Run tests to verify the new ones fail and the rest still pass**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py -v`
Expected: the 3 new tests FAIL with `ImportError`/`AttributeError` (no `read_pdf_pages` yet); the ~20 existing tests still PASS (confirms the `_FakePage` extension is backward compatible).

- [ ] **Step 3: Implement `read_pdf_pages`, refactor `read_pdf_page_texts` to use it**

In `cabinet_reference_reader.py`, replace the existing `read_pdf_page_texts` function (lines 138-174) with:

```python
def read_pdf_pages(
    pdf_path: Path,
    *,
    opener: Callable[[str], Any] = open_pdf_with_breadcrumb,
) -> tuple[tuple[str, ...], tuple[int, ...]]:
    """Extract text and a drawing-presence signal for every page, in one pass.

    The signal is ``len(page.get_drawings()) + len(page.get_images())`` --
    used downstream (see ``cabinet_drawing_resolver``) to tell an
    elevation/assembly drawing page apart from a same-context page that only
    carries table/BOM rows and no picture. Always closes the document,
    mirroring the previous ``read_pdf_page_texts`` behavior.
    """

    document: Any | None = None
    failure: CabinetIndexBuildError | None = None
    texts: tuple[str, ...] = ()
    signals: tuple[int, ...] = ()
    try:
        try:
            document = opener(str(pdf_path))
        except Exception as exc:
            failure = CabinetIndexBuildError("pdf_open", pdf_path.name, str(exc))
        if failure is None:
            extracted_texts: list[str] = []
            extracted_signals: list[int] = []
            try:
                for page in document:
                    text = page.get_text()
                    if not isinstance(text, str):
                        raise TypeError(f"page text is {type(text).__name__}, expected str")
                    extracted_texts.append(text)
                    extracted_signals.append(len(page.get_drawings()) + len(page.get_images()))
                texts = tuple(extracted_texts)
                signals = tuple(extracted_signals)
            except Exception as exc:
                failure = CabinetIndexBuildError("extraction", pdf_path.name, str(exc))
    finally:
        if document is not None:
            try:
                document.close()
            except Exception as exc:
                if failure is None:
                    failure = CabinetIndexBuildError("pdf_close", pdf_path.name, str(exc))

    if failure is not None:
        raise failure
    return texts, signals


def read_pdf_page_texts(
    pdf_path: Path,
    *,
    opener: Callable[[str], Any] = open_pdf_with_breadcrumb,
) -> tuple[str, ...]:
    """Extract one text string per PDF page and always close the document."""

    texts, _signals = read_pdf_pages(pdf_path, opener=opener)
    return texts
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py -v`
Expected: PASS (all existing + 3 new tests)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/adapters/cabinet_reference_reader.py backend/tests/test_ready_jobs_worker_core_cabinet_reference_reader.py
git commit -m "feat: extract per-page drawing signal alongside PDF text

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Wire drawing resolution into the raw assembly and plans documents

**Files:**
- Modify: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\cabinet_reference_reader.py` (imports, `predict_cabinet_reference_index`)
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_cabinet_reference_reader.py`

This makes `documents.assembly.pageDetails` (single/primary-variant view) and `documents.plansElevations.pageDetails` carry `hasDrawing`/`drawingPage`. Task 4 covers `virtualCombined.pageDetails` separately, since it uses its own page numbering.

- [ ] **Step 1: Write the failing tests**

Add to `test_ready_jobs_worker_core_cabinet_reference_reader.py`:

```python
def test_prediction_resolves_drawing_page_for_plans_spillover(tmp_path: Path) -> None:
    job = tmp_path / "100 - Alpha"
    plans = _touch(job / "100 - Plans & Elevations.pdf")
    documents = {
        str(plans): _FakeDocument(
            (
                _FakePage("||CAB:5|| ||WALL:Room #1 - Wall #5||", drawings=500),
                _FakePage("||CAB:12|| ||WALL:Room #1 - Wall #5||", drawings=10),
            )
        ),
    }

    prediction = predict_cabinet_reference_index(job, opener=lambda path: documents[path])

    plans_payload = prediction.payload["documents"]["plansElevations"]
    assert plans_payload["pageDetails"]["1"]["drawingPage"] == 1
    assert plans_payload["pageDetails"]["1"]["hasDrawing"] is True
    assert plans_payload["pageDetails"]["2"]["drawingPage"] == 1
    assert plans_payload["pageDetails"]["2"]["hasDrawing"] is False


def test_prediction_resolves_drawing_page_for_assembly_primary_document(tmp_path: Path) -> None:
    job = tmp_path / "100 - Alpha"
    assembly = _touch(job / "100 - Assembly Sheets.pdf")
    documents = {
        str(assembly): _FakeDocument(
            (
                _FakePage("||CAB:1||", drawings=900),
                _FakePage("||CAB:1||", drawings=50),
            )
        ),
    }

    prediction = predict_cabinet_reference_index(job, opener=lambda path: documents[path])

    assembly_payload = prediction.payload["documents"]["assembly"]
    assert assembly_payload["pageDetails"]["1"]["drawingPage"] == 1
    assert assembly_payload["pageDetails"]["1"]["hasDrawing"] is True
    assert assembly_payload["pageDetails"]["2"]["drawingPage"] == 1
    assert assembly_payload["pageDetails"]["2"]["hasDrawing"] is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py -k drawing_page -v`
Expected: FAIL with `KeyError: 'drawingPage'`

- [ ] **Step 3: Wire the resolution into `predict_cabinet_reference_index`**

In `cabinet_reference_reader.py`, update the import block (around line 22-31) to add the resolver import:

```python
from ..cabinet_drawing_resolver import apply_drawing_resolution, group_pages_by_room_wall
from ..cabinet_reference_parser import (
    DeliveryMetadata,
    ParsedReferenceDocument,
    build_virtual_combined_assembly,
    mode_from_assembly_variants,
    parse_assembly_pages,
    parse_delivery_pages,
    parse_plans_pages,
)
```

Replace the assembly-variant parsing loop inside `predict_cabinet_reference_index` (currently):

```python
    assembly_documents: dict[str, tuple[Path, ParsedReferenceDocument]] = {}
    for variant, path in selection.assembly_by_variant.items():
        try:
            pages = read_pdf_page_texts(path, opener=opener)
            parsed = parse_assembly_pages(pages)
        except CabinetIndexBuildError:
            raise
        except Exception as exc:
            raise CabinetIndexBuildError("parsing", path.name, str(exc)) from exc
        validated = _validate_parsed_document(path, parsed, ParsedReferenceDocument)
        assembly_documents[variant] = (path, validated)  # type: ignore[assignment]
```

with:

```python
    assembly_documents: dict[str, tuple[Path, ParsedReferenceDocument]] = {}
    assembly_signals: dict[str, tuple[int, ...]] = {}
    for variant, path in selection.assembly_by_variant.items():
        try:
            page_texts, page_signals = read_pdf_pages(path, opener=opener)
            parsed = parse_assembly_pages(page_texts)
        except CabinetIndexBuildError:
            raise
        except Exception as exc:
            raise CabinetIndexBuildError("parsing", path.name, str(exc)) from exc
        validated = _validate_parsed_document(path, parsed, ParsedReferenceDocument)
        apply_drawing_resolution(
            validated.page_details,  # type: ignore[arg-type]
            groups=list(validated.cabinet_to_pages.values()),  # type: ignore[union-attr]
            page_signals=dict(enumerate(page_signals, start=1)),
        )
        assembly_documents[variant] = (path, validated)  # type: ignore[assignment]
        assembly_signals[variant] = page_signals
```

Replace the plans-document parsing block (currently):

```python
    plans_document: ParsedReferenceDocument | None = None
    if selection.plans is not None:
        path = selection.plans
        try:
            pages = read_pdf_page_texts(path, opener=opener)
            parsed = parse_plans_pages(pages)
        except CabinetIndexBuildError:
            raise
        except Exception as exc:
            raise CabinetIndexBuildError("parsing", path.name, str(exc)) from exc
        plans_document = _validate_parsed_document(path, parsed, ParsedReferenceDocument)  # type: ignore[assignment]
```

with:

```python
    plans_document: ParsedReferenceDocument | None = None
    if selection.plans is not None:
        path = selection.plans
        try:
            page_texts, page_signals = read_pdf_pages(path, opener=opener)
            parsed = parse_plans_pages(page_texts)
        except CabinetIndexBuildError:
            raise
        except Exception as exc:
            raise CabinetIndexBuildError("parsing", path.name, str(exc)) from exc
        plans_document = _validate_parsed_document(path, parsed, ParsedReferenceDocument)  # type: ignore[assignment]
        apply_drawing_resolution(
            plans_document.page_details,  # type: ignore[union-attr]
            groups=group_pages_by_room_wall(plans_document.page_details),  # type: ignore[union-attr]
            page_signals=dict(enumerate(page_signals, start=1)),
        )
```

Leave the delivery-document parsing block untouched (delivery has no drawing/spillover concept — it keeps using `read_pdf_page_texts`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py -v`
Expected: PASS (all tests, including the 2 new ones)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/adapters/cabinet_reference_reader.py backend/tests/test_ready_jobs_worker_core_cabinet_reference_reader.py
git commit -m "feat: resolve drawing pages for primary assembly and plans documents

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Wire drawing resolution into `virtualCombined.pageDetails`

**Files:**
- Modify: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\cabinet_reference_reader.py` (`_assemble_payload`, its call site)
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_cabinet_reference_reader.py`

Android's assembly-sheet jump navigator reads `virtualCombined.cabinetToPages`/`pageDetails` (virtual page numbers spanning FF/FL variants), not the raw per-variant document. `virtualCombined["cabinetToPages"]` already groups a cabinet's virtual pages together, and `virtualCombined["virtualPageToSource"]` maps each virtual page back to its `(variant, raw page)` — enough to look up that raw page's signal from `assembly_signals` (built in Task 3) and resolve in virtual-page space.

- [ ] **Step 1: Write the failing test**

Add to `test_ready_jobs_worker_core_cabinet_reference_reader.py`:

```python
def test_prediction_resolves_drawing_page_for_assembly_virtual_combined(tmp_path: Path) -> None:
    job = tmp_path / "100 - Alpha"
    assembly = _touch(job / "100 - Assembly Sheets.pdf")
    documents = {
        str(assembly): _FakeDocument(
            (
                _FakePage("||CAB:1||", drawings=900),
                _FakePage("||CAB:1||", drawings=50),
            )
        ),
    }

    prediction = predict_cabinet_reference_index(job, opener=lambda path: documents[path])

    virtual = prediction.payload["documents"]["assembly"]["virtualCombined"]
    assert virtual["pageDetails"]["1"]["drawingPage"] == 1
    assert virtual["pageDetails"]["1"]["hasDrawing"] is True
    assert virtual["pageDetails"]["2"]["drawingPage"] == 1
    assert virtual["pageDetails"]["2"]["hasDrawing"] is False


def test_prediction_resolves_drawing_page_across_ff_fl_variants_in_virtual_combined(tmp_path: Path) -> None:
    job = tmp_path / "100 - Alpha"
    face_frame = _touch(job / "100 - Assembly Sheets FF.pdf")
    frameless = _touch(job / "100 - Assembly Sheets FL.pdf")
    documents = {
        str(face_frame): _FakeDocument((_FakePage("||CAB:2||", drawings=800),)),
        str(frameless): _FakeDocument((_FakePage("||CAB:2||", drawings=20),)),
    }

    prediction = predict_cabinet_reference_index(job, opener=lambda path: documents[path])

    virtual = prediction.payload["documents"]["assembly"]["virtualCombined"]
    # cabinet "2": FACE_FRAME's virtual page 1 (signal 800) beats FRAMELESS's virtual page 2 (signal 20)
    assert virtual["cabinetToPages"]["2"] == [1, 2]
    assert virtual["pageDetails"]["1"]["drawingPage"] == 1
    assert virtual["pageDetails"]["2"]["drawingPage"] == 1
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py -k virtual_combined -v`
Expected: FAIL with `KeyError: 'drawingPage'`

- [ ] **Step 3: Enrich `virtualCombined` in `_assemble_payload`**

Add `assembly_signals` as a parameter to `_assemble_payload`'s signature (currently ends `generated_at: str,`):

```python
def _assemble_payload(
    selection: ReferenceDocumentSelection,
    *,
    assembly_documents: Mapping[str, tuple[Path, ParsedReferenceDocument]],
    plans_document: ParsedReferenceDocument | None,
    delivery_document: DeliveryMetadata | None,
    assembly_signals: Mapping[str, tuple[int, ...]],
    generated_at: str,
) -> dict[str, object]:
```

Right after the existing `virtual_combined = build_virtual_combined_assembly(virtual_sources)` line, insert:

```python
    if assembly_sources:
        virtual_signal_map: dict[int, int] = {}
        for vp_key, source in virtual_combined.get("virtualPageToSource", {}).items():
            variant = source.get("variant")
            raw_page = source.get("page")
            signals = assembly_signals.get(variant, ())
            if isinstance(raw_page, int) and 1 <= raw_page <= len(signals):
                virtual_signal_map[int(vp_key)] = signals[raw_page - 1]
        apply_drawing_resolution(
            virtual_combined.get("pageDetails", {}),  # type: ignore[arg-type]
            groups=list(virtual_combined.get("cabinetToPages", {}).values()),  # type: ignore[arg-type]
            page_signals=virtual_signal_map,
        )
```

Update the one call site of `_assemble_payload` (inside `predict_cabinet_reference_index`, near the bottom) to pass the new argument:

```python
    try:
        payload = _assemble_payload(
            selection,
            assembly_documents=assembly_documents,
            plans_document=plans_document,
            delivery_document=delivery_document,
            assembly_signals=assembly_signals,
            generated_at=generated,
        )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py -v`
Expected: PASS (all tests)

Then run the full backend suite to confirm no regressions elsewhere:

Run: `cd "C:\Scripts\Hours Tracker\backend" && venv\Scripts\python.exe -m pytest tests/test_ready_jobs_worker_core_cabinet_reference_reader.py tests/test_ready_jobs_worker_core_cabinet_reference_publish.py tests/test_ready_jobs_worker_core_cabinet_reference_parser.py tests/test_ready_jobs_worker_core_cabinet_drawing_resolver.py -v`
Expected: PASS (all)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/adapters/cabinet_reference_reader.py backend/tests/test_ready_jobs_worker_core_cabinet_reference_reader.py
git commit -m "feat: resolve drawing pages in the virtual combined assembly index

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 6 (manual verification, not automated): regenerate job 669's index**

This confirms the change against the real PDF investigated during design. Requires the Ready Jobs Watcher venv (has PyMuPDF) since Hours Tracker's own venv may not have direct filesystem access configured the same way — adjust if `ready_jobs_worker_core` is importable from that venv instead.

```powershell
cd "C:\Scripts\Hours Tracker\backend"
venv\Scripts\python.exe -c "from pathlib import Path; from ready_jobs_worker_core.adapters.cabinet_reference_publish import refresh_cabinet_reference_index; result = refresh_cabinet_reference_index(Path(r'Y:\Ready Jobs\669 - WIECHERT 3146 NW CROSSINGS'), dry_run=True); print(result)"
```

Then inspect `Y:\Ready Jobs\669 - WIECHERT 3146 NW CROSSINGS\.metadata\cabinet_sheet_index.json` after a real (non-dry-run) refresh and confirm `documents.plansElevations.pageDetails["6"]` has `"drawingPage": 5, "hasDrawing": false`.

---

### Task 5: Add `hasDrawing`/`drawingPage` to the tablet's `CabinetPageDetail` model

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\models\Models.kt:131-139`
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\unified\FileBackedUnifiedMetadataEngine.kt:1542-1563`
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\unified\UnifiedMetadataEngineTest.kt`

Gson deserializes `CabinetPageDetail` reflectively (no `@SerializedName` used anywhere in `Models.kt`), so Kotlin field names must match the JSON keys `hasDrawing`/`drawingPage` exactly. `FileBackedUnifiedMetadataEngine.sanitizeCabinetPageDetail` rebuilds the object field-by-field as a whitelist — any field not explicitly copied there is silently dropped, so it must be updated too.

- [ ] **Step 1: Write the failing test**

Add to `UnifiedMetadataEngineTest.kt` (same file already has a `createTempBaseDir()` helper used elsewhere in the file — reuse it):

```kotlin
@Test
fun cabinetSheetIndex_preservesDrawingPageResolutionThroughSanitize() {
    val baseDir = createTempBaseDir()
    val jobFolder = "1234 - Test Job"
    val jobDir = File(baseDir, jobFolder).apply { mkdirs() }
    val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
    File(metadataDir, "deployment_gate.json").writeText("""{"deployed": true}""")
    File(metadataDir, "cabinet_sheet_index.json").writeText(
        """
        {
          "documents": {
            "assembly": { "pdfFilename": "", "cabinetToPages": {}, "pageDetails": {} },
            "plansElevations": {
              "pdfFilename": "1234 - Plans & Elevations.pdf",
              "cabinetToPages": { "12": [6] },
              "pageDetails": {
                "5": { "cabinets": ["5"], "room": "Room #1", "wall": "Wall #5", "hasDrawing": true, "drawingPage": 5 },
                "6": { "cabinets": ["12"], "room": "Room #1", "wall": "Wall #5", "hasDrawing": false, "drawingPage": 5 }
              }
            },
            "delivery": {}
          }
        }
        """.trimIndent()
    )

    val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)
    val index = engine.getCabinetSheetIndex(jobFolder).index

    val page6 = index?.documents?.plansElevations?.pageDetails?.get("6")
    assertEquals(5, page6?.drawingPage)
    assertEquals(false, page6?.hasDrawing)
    val page5 = index?.documents?.plansElevations?.pageDetails?.get("5")
    assertEquals(5, page5?.drawingPage)
    assertEquals(true, page5?.hasDrawing)
}
```

Check the top of `UnifiedMetadataEngineTest.kt` for its existing `createTempBaseDir()` signature/imports and match them (it's used by other tests in this file already).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest"`
Expected: FAIL to compile (`hasDrawing`/`drawingPage` unresolved references on `CabinetPageDetail`)

- [ ] **Step 3: Add the fields**

In `Models.kt`, replace the `CabinetPageDetail` data class (lines 131-139):

```kotlin
data class CabinetPageDetail(
    val cabinets: List<String> = emptyList(),
    val room: String? = null,
    val wall: String? = null,
    val parts: List<AssemblySheetPart> = emptyList(),
    val sourceVariant: String? = null,
    val sourcePdfFilename: String? = null,
    val sourcePage: Int? = null,
    val hasDrawing: Boolean = false,
    val drawingPage: Int? = null
)
```

In `FileBackedUnifiedMetadataEngine.kt`, replace the `sanitizeCabinetPageDetail` function (lines 1542-1563):

```kotlin
    private fun sanitizeCabinetPageDetail(detail: CabinetPageDetail?): CabinetPageDetail {
        if (detail == null) return CabinetPageDetail()
        return CabinetPageDetail(
            cabinets = gsonNullable(detail.cabinets) ?: emptyList(),
            room = detail.room,
            wall = detail.wall,
            parts = (gsonNullable(detail.parts) ?: emptyList()).map { part ->
                AssemblySheetPart(
                    qty = part.qty,
                    width = part.width,
                    length = part.length,
                    description = gsonNullable(part.description) ?: "",
                    material = gsonNullable(part.material) ?: "",
                    sectionType = gsonNullable(part.sectionType) ?: "",
                    isPurchased = part.isPurchased
                )
            },
            sourceVariant = detail.sourceVariant,
            sourcePdfFilename = detail.sourcePdfFilename,
            sourcePage = detail.sourcePage,
            hasDrawing = detail.hasDrawing,
            drawingPage = detail.drawingPage
        )
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt app/src/test/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngineTest.kt
git commit -m "feat: carry hasDrawing/drawingPage through cabinet sheet index parsing

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Expose the resolved page details to the reference viewer

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\viewer\ReferenceViewerData.kt`

`navigatorCabinetToPages` (line 16, populated at lines 96-110) already picks the right page-number space per doc type — raw for Plans & Elevations, virtual for Assembly when combined FF/FL data exists. Add a parallel `navigatorPageDetails` following the exact same branching, so `resolveJumpPage` (Task 7) can look up `drawingPage` in the matching page-number space.

- [ ] **Step 1: Add the field and its computation**

In `ReferenceViewerData.kt`, add the import:

```kotlin
import com.kkc.sheettracker.data.models.CabinetPageDetail
```

Add a field to the `ReferenceViewerData` data class (after `navigatorCabinetToPages`):

```kotlin
data class ReferenceViewerData(
    val defaultPdfFilename: String,
    val virtualMapping: UnifiedVirtualPageMapping?,
    val navigatorCabinetToPages: Map<String, List<Int>>,
    val navigatorPageDetails: Map<String, CabinetPageDetail>,
    val navigatorPlanViewLabels: Map<Int, String>,
    val warningMessage: String?
)
```

Add its computation right after the existing `navigatorCabinetToPages` `remember` block (after line 110):

```kotlin
    val navigatorPageDetails = remember(docType, documentIndex, virtualMapping, sheetIndex) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> {
                if (virtualMapping != null) {
                    sheetIndex?.documents?.assembly?.virtualCombined?.pageDetails.orEmpty()
                } else {
                    documentIndex?.pageDetails.orEmpty()
                }
            }
            ReferenceDocType.PLANS_ELEVATIONS -> documentIndex?.pageDetails.orEmpty()
            ReferenceDocType.DELIVERY_SHEETS -> emptyMap()
            ReferenceDocType.SHEET -> emptyMap()
            ReferenceDocType.PULLS -> emptyMap()
        }
    }
```

Add it to the returned `ReferenceViewerData(...)` at the bottom of the function:

```kotlin
    return ReferenceViewerData(
        defaultPdfFilename = defaultPdfFilename,
        virtualMapping = virtualMapping,
        navigatorCabinetToPages = navigatorCabinetToPages,
        navigatorPageDetails = navigatorPageDetails,
        navigatorPlanViewLabels = navigatorPlanViewLabels,
        warningMessage = if (docType == ReferenceDocType.ASSEMBLY) {
            assemblyVirtualSanitized.warningMessage
        } else {
            null
        }
    )
```

This constructor-only change won't compile until Task 7 updates the one other place that constructs `ReferenceViewerData` (the `SHEET` tab branch in `ReferenceModalOverlay.kt`) — that's covered in the next task, so build verification happens there.

- [ ] **Step 2: Commit**

This task has no standalone test (it's a plumbing addition with no new logic — the branching it mirrors is already covered by existing `navigatorCabinetToPages` tests, and the new logic gets exercised by Task 7's `resolveJumpPage` tests). Commit together with Task 7 instead of separately — skip straight to Task 7.

---

### Task 7: Prefer the resolved drawing page when jumping to a cabinet

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\components\ReferenceModalOverlay.kt:116-117` (`resolveJumpPage`), `:287-293` (SHEET-branch `ReferenceViewerData` construction), `:317` (call site)
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\ui\components\ReferenceModalStateTest.kt:52-64`

- [ ] **Step 1: Update the existing tests for the new signature and add spillover-redirect coverage**

Replace the two existing `resolveJumpPage` tests in `ReferenceModalStateTest.kt` (lines 52-64) and add new ones:

```kotlin
@Test
fun resolveJumpPage_returnsFirstPageForCabinetWhenNoPageDetails() {
    val map = mapOf("3" to listOf(5, 6), "8" to listOf(11))
    assertEquals(5, resolveJumpPage(map, emptyMap(), 3))
    assertEquals(11, resolveJumpPage(map, emptyMap(), 8))
}

@Test
fun resolveJumpPage_returnsNullWhenCabinetAbsent() {
    val map = mapOf("3" to listOf(5))
    assertNull(resolveJumpPage(map, emptyMap(), 99))
    assertNull(resolveJumpPage(emptyMap(), emptyMap(), 3))
}

@Test
fun resolveJumpPage_redirectsFromSpilloverPageToDrawingPage() {
    val map = mapOf("12" to listOf(6))
    val details = mapOf(
        "6" to CabinetPageDetail(cabinets = listOf("12"), hasDrawing = false, drawingPage = 5)
    )
    assertEquals(5, resolveJumpPage(map, details, 12))
}

@Test
fun resolveJumpPage_staysOnPageThatAlreadyHasTheDrawing() {
    val map = mapOf("5" to listOf(5))
    val details = mapOf(
        "5" to CabinetPageDetail(cabinets = listOf("5"), hasDrawing = true, drawingPage = 5)
    )
    assertEquals(5, resolveJumpPage(map, details, 5))
}

@Test
fun resolveJumpPage_fallsBackToRawPageWhenPageDetailsMissingForThatPage() {
    val map = mapOf("3" to listOf(5))
    assertEquals(5, resolveJumpPage(map, emptyMap(), 3))
}
```

Add the import at the top of the test file:

```kotlin
import com.kkc.sheettracker.data.models.CabinetPageDetail
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"`
Expected: FAIL to compile — `resolveJumpPage` still takes 2 args, not 3

- [ ] **Step 3: Update `resolveJumpPage` and its call sites**

In `ReferenceModalOverlay.kt`, add the import:

```kotlin
import com.kkc.sheettracker.data.models.CabinetPageDetail
```

Replace `resolveJumpPage` (lines 116-117):

```kotlin
/** Drawing-page-preferring jump target for [cabinet] in the active doc's page space, or null if none.
 *
 * Cabinet numbers spilling onto a following table/BOM-only page (Cabinet Vision emits these when
 * too much doesn't fit on the drawing page) still resolve here, but redirect to the page that
 * actually carries the drawing when the backend index knows one (see `CabinetPageDetail.drawingPage`).
 */
fun resolveJumpPage(
    cabinetToPages: Map<String, List<Int>>,
    pageDetails: Map<String, CabinetPageDetail>,
    cabinet: Int
): Int? {
    val page = cabinetToPages[cabinet.toString()]?.firstOrNull() ?: return null
    return pageDetails[page.toString()]?.drawingPage ?: page
}
```

Update the SHEET-branch `ReferenceViewerData` construction (lines 287-293):

```kotlin
        ReferenceViewerData(
            defaultPdfFilename = sheetPdfFilename,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPageDetails = emptyMap(),
            navigatorPlanViewLabels = emptyMap(),
            warningMessage = null
        )
```

Update the call site (line 317):

```kotlin
        val target = resolveJumpPage(referenceData.navigatorCabinetToPages, referenceData.navigatorPageDetails, cabinet)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"`
Expected: PASS

Then run the broader reference-viewer test surface to catch any other `ReferenceViewerData`/`resolveJumpPage` consumer:

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.*" --tests "com.kkc.sheettracker.ui.components.*"`
Expected: PASS (no other test references the old 2-arg `resolveJumpPage` or is missing the new `ReferenceViewerData` field)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt
git commit -m "feat: jump to a cabinet's drawing page instead of its spillover page

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Manual verification on device

Not automatable — confirms the end-to-end behavior the whole plan exists for.

- [ ] **Step 1: Regenerate the index for job 669** (see Task 4 Step 6) so `Y:\Ready Jobs\669 - WIECHERT 3146 NW CROSSINGS\.metadata\cabinet_sheet_index.json` has the new fields.
- [ ] **Step 2: Deploy the tablet build.** Run: `.\adb-install-release.ps1` (per `CLAUDE.md`; if it fails on unicode output, fall back to `gradlew.bat assembleRelease` + `adb install -r` directly per the `adb_release_script_encoding` memory).
- [ ] **Step 3: On the tablet**, open job 669, open the Plans & Elevations reference viewer, and search/jump to cabinet 12.
  Expected: lands on page 5 (the Wall #5 elevation drawing), not page 6 (the table-only spillover page).
- [ ] **Step 4: Repeat in continuous scroll mode** (toggle the view mode if the viewer defaults to page view) — same cabinet, same expected landing page 5.
- [ ] **Step 5: Jump to a cabinet that already has its own drawing** (e.g. cabinet 5) and confirm it still lands on its own page (page 5) — no regression for the common case.
