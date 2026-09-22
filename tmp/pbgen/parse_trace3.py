import struct
import sys
from collections import defaultdict

import cmd_report_sample_pb2 as pb

PATH = sys.argv[1] if len(sys.argv) > 1 else r"C:\Scripts\KKCSheetTracker\cpu-simpleperf-20260917T111452.trace"

with open(PATH, "rb") as f:
    data = f.read()

assert data[:10] == b"SIMPLEPERF", data[:10]
pos = 12
files = {}
threads = {}
meta_info = None
samples = []

while pos + 4 <= len(data):
    size = struct.unpack_from("<I", data, pos)[0]
    pos += 4
    if size == 0:
        break
    chunk = data[pos:pos+size]
    pos += size
    rec = pb.Record()
    rec.ParseFromString(chunk)
    which = rec.WhichOneof("record_data")
    if which == "file":
        files[rec.file.id] = rec.file
    elif which == "thread":
        threads[rec.thread.thread_id] = rec.thread
    elif which == "meta_info":
        meta_info = rec.meta_info
    elif which == "sample":
        samples.append(rec.sample)

event_types = list(meta_info.event_type)
print("event_types:", event_types)
cpu_clock_id = event_types.index("cpu-clock") if "cpu-clock" in event_types else None
print("cpu_clock_id:", cpu_clock_id)

def sym_name(file_id, symbol_id):
    f = files.get(file_id)
    if f is None:
        return f"<unknown file {file_id}>"
    if symbol_id is None or symbol_id < 0 or symbol_id >= len(f.symbol):
        return "<unknown sym>"
    return f.symbol[symbol_id]

def file_short(file_id):
    f = files.get(file_id)
    if not f:
        return f"<unknown {file_id}>"
    p = f.path
    return p.split("/")[-1]

cpu_clock_samples_by_thread = defaultdict(int)
self_count_by_thread = defaultdict(lambda: defaultdict(int))
appcode_frame_count_by_thread = defaultdict(int)
top_appcode_frames = defaultdict(int)
callchain_sig_count = defaultdict(int)

for s in samples:
    if cpu_clock_id is not None and s.event_type_id != cpu_clock_id:
        continue
    tid = s.thread_id
    cpu_clock_samples_by_thread[tid] += 1
    if len(s.callchain) == 0:
        continue
    leaf = s.callchain[0]
    key = (file_short(leaf.file_id), sym_name(leaf.file_id, leaf.symbol_id))
    self_count_by_thread[tid][key] += 1

    # scan whole callchain for any app-code frame
    found_app = False
    for frame in s.callchain:
        nm = sym_name(frame.file_id, frame.symbol_id)
        if "com.kkc.sheettracker" in nm or "kkc.sheettracker" in file_short(frame.file_id):
            found_app = True
            top_appcode_frames[(file_short(frame.file_id), nm)] += 1
    if found_app:
        appcode_frame_count_by_thread[tid] += 1

    # build a short signature of top 4 frames for pattern grouping
    sig = tuple(sym_name(fr.file_id, fr.symbol_id) for fr in s.callchain[:4])
    callchain_sig_count[(tid, sig)] += 1

print("\n=== cpu-clock sample counts per thread (ON-CPU only) ===")
for tid, cnt in sorted(cpu_clock_samples_by_thread.items(), key=lambda x: -x[1])[:20]:
    name = threads.get(tid)
    name = name.thread_name if name else "?"
    appcnt = appcode_frame_count_by_thread.get(tid, 0)
    print(f"tid={tid:6d} name={name:25s} on_cpu_samples={cnt:6d}  samples_with_appcode_frame={appcnt}")

def report(tids, label):
    combined = defaultdict(int)
    for tid in tids:
        for key, cnt in self_count_by_thread[tid].items():
            combined[key] += cnt
    total = sum(combined.values())
    print(f"\n=== {label}: total ON-CPU samples = {total} ===")
    for key, cnt in sorted(combined.items(), key=lambda x: -x[1])[:30]:
        pct = 100.0 * cnt / total if total else 0
        print(f"{cnt:6d} ({pct:5.2f}%)  [{key[0]}] {key[1]}")

# auto-pick main thread: highest sample count thread whose name doesn't look like a worker/GC/binder pool
main_tid = None
for tid, cnt in sorted(cpu_clock_samples_by_thread.items(), key=lambda x: -x[1]):
    name = threads.get(tid)
    name = name.thread_name if name else ""
    if name in ("com.kkc.sheettracker",) or name.endswith("sheettracker"):
        main_tid = tid
        break
if main_tid is None:
    # fallback: just the busiest thread
    main_tid = sorted(cpu_clock_samples_by_thread.items(), key=lambda x: -x[1])[0][0]

print(f"\n(auto-picked main_tid={main_tid} name={threads.get(main_tid).thread_name if threads.get(main_tid) else '?'})")
report([main_tid], "MAIN THREAD (auto)")

print(f"\n=== top app-code frames seen ANYWHERE in callchain (any thread) ===")
for key, cnt in sorted(top_appcode_frames.items(), key=lambda x: -x[1])[:40]:
    print(f"{cnt:6d}  [{key[0]}] {key[1]}")

print(f"\n=== top callchain signatures (tid, top4 frames) on main thread ===")
main_sigs = {k: v for k, v in callchain_sig_count.items() if k[0] == main_tid}
for (tid, sig), cnt in sorted(main_sigs.items(), key=lambda x: -x[1])[:20]:
    print(f"{cnt:6d}  {' <- '.join(sig)}")
