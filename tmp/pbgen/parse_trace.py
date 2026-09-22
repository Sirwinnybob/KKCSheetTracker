import struct
import sys
from collections import defaultdict

import cmd_report_sample_pb2 as pb

PATH = r"C:\Scripts\KKCSheetTracker\cpu-simpleperf-20260917T073552.trace"

with open(PATH, "rb") as f:
    data = f.read()

assert data[:10] == b"SIMPLEPERF", data[:10]
version = struct.unpack_from("<H", data, 10)[0]
print("version", version, "file size", len(data))

pos = 12
files = {}       # file_id -> File proto (path, symbol list, mangled_symbol list)
threads = {}     # thread_id -> Thread proto
meta_info = None
samples = []     # list of Sample proto
lost_total = 0
record_count = 0

while pos + 4 <= len(data):
    size = struct.unpack_from("<I", data, pos)[0]
    pos += 4
    if size == 0:
        break
    chunk = data[pos:pos+size]
    pos += size
    rec = pb.Record()
    rec.ParseFromString(chunk)
    record_count += 1
    which = rec.WhichOneof("record_data")
    if which == "file":
        files[rec.file.id] = rec.file
    elif which == "thread":
        threads[rec.thread.thread_id] = rec.thread
    elif which == "meta_info":
        meta_info = rec.meta_info
    elif which == "sample":
        samples.append(rec.sample)
    elif which == "lost":
        lost_total += rec.lost.lost_count

print("records:", record_count, "samples:", len(samples), "files:", len(files), "threads:", len(threads), "lost:", lost_total)
if meta_info:
    print("meta_info:", meta_info)

print("\n=== threads ===")
for tid, t in threads.items():
    print(tid, t.thread_name)

def sym_name(file_id, symbol_id):
    f = files.get(file_id)
    if f is None:
        return f"<unknown file {file_id}>"
    if symbol_id is None or symbol_id < 0 or symbol_id >= len(f.symbol):
        return f"<unknown sym in {f.path}>"
    return f.symbol[symbol_id]

def file_path(file_id):
    f = files.get(file_id)
    return f.path if f else f"<unknown file {file_id}>"

# Find main thread tid(s) by name
main_tids = [tid for tid, t in threads.items() if t.thread_name == "kc.sheettracker" or t.thread_name == "main"]
print("\nmain-ish tids:", [(tid, threads[tid].thread_name) for tid in main_tids])

# Self-time = leaf frame of callchain (callchain[0] is innermost per simpleperf convention)
self_count = defaultdict(int)
self_count_by_thread = defaultdict(lambda: defaultdict(int))
total_samples_per_thread = defaultdict(int)

for s in samples:
    tid = s.thread_id
    total_samples_per_thread[tid] += 1
    if len(s.callchain) == 0:
        continue
    leaf = s.callchain[0]
    key = (file_path(leaf.file_id), sym_name(leaf.file_id, leaf.symbol_id))
    self_count[key] += 1
    self_count_by_thread[tid][key] += 1

print("\n=== sample counts per thread ===")
for tid, cnt in sorted(total_samples_per_thread.items(), key=lambda x: -x[1]):
    name = threads.get(tid, None)
    name = name.thread_name if name else "?"
    print(f"tid={tid:6d} name={name:25s} samples={cnt}")

print("\n=== TOP self-time leaf symbols, ALL THREADS ===")
total = sum(self_count.values())
for key, cnt in sorted(self_count.items(), key=lambda x: -x[1])[:40]:
    pct = 100.0 * cnt / total if total else 0
    print(f"{cnt:6d} ({pct:5.2f}%)  {key[0]}  ::  {key[1]}")

if main_tids:
    print("\n=== TOP self-time leaf symbols, MAIN THREAD ONLY ===")
    combined = defaultdict(int)
    for tid in main_tids:
        for key, cnt in self_count_by_thread[tid].items():
            combined[key] += cnt
    total_main = sum(combined.values())
    for key, cnt in sorted(combined.items(), key=lambda x: -x[1])[:40]:
        pct = 100.0 * cnt / total_main if total_main else 0
        print(f"{cnt:6d} ({pct:5.2f}%)  {key[0]}  ::  {key[1]}")
