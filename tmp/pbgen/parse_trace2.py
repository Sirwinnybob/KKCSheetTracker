import struct
from collections import defaultdict

import cmd_report_sample_pb2 as pb

PATH = r"C:\Scripts\KKCSheetTracker\cpu-simpleperf-20260917T073552.trace"

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

self_count_by_thread = defaultdict(lambda: defaultdict(int))
cpu_clock_samples_by_thread = defaultdict(int)

for s in samples:
    if cpu_clock_id is not None and s.event_type_id != cpu_clock_id:
        continue  # skip sched_switch (off-cpu) samples, keep only real cpu-clock samples
    tid = s.thread_id
    cpu_clock_samples_by_thread[tid] += 1
    if len(s.callchain) == 0:
        continue
    leaf = s.callchain[0]
    key = (file_short(leaf.file_id), sym_name(leaf.file_id, leaf.symbol_id))
    self_count_by_thread[tid][key] += 1

print("\n=== cpu-clock sample counts per thread (ON-CPU only) ===")
for tid, cnt in sorted(cpu_clock_samples_by_thread.items(), key=lambda x: -x[1])[:15]:
    name = threads.get(tid)
    name = name.thread_name if name else "?"
    print(f"tid={tid:6d} name={name:25s} on_cpu_samples={cnt}")

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

report([25447], "MAIN THREAD (com.kkc.sheettracker)")
report([25475], "RenderThread (25475)")
