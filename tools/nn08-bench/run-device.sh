#!/bin/sh
# NN-0.8 bench, on the device (Odin: /data/local/tmp/nn08; Deck: ~/nn08).
# Usage: ./run-device.sh [cpu]   (the core to pin: the Odin's prime core is 7)
# Writes nn08-<host>-<time>.txt next to the binaries. Runs the full bench with
# each binary present (nn08_bench, and nn08_bench_v3 on the Deck), then, when
# the bench has no counters of its own (Android without perf access), each
# variant alone under simpleperf/perf stat for instructions and cycles.
cd "$(dirname "$0")" || exit 1
CPU=${1:-2}
OUT="nn08-$(uname -n)-$(date +%Y%m%d-%H%M%S).txt"
echo "# $(uname -a)" > "$OUT"
# The cores this shell may use: on the Odin, adb shell cpusets have refused
# --cpu 7 (sched_setaffinity EINVAL), which leaves the run unpinned.
echo "# $(grep Cpus_allowed_list /proc/self/status)" >> "$OUT"
for BIN in ./nn08_bench ./nn08_bench_v3; do
  [ -x "$BIN" ] || continue
  echo "### $BIN" | tee -a "$OUT"
  "$BIN" --repeats 9 --ms 50 --cpu "$CPU" --csv 2>&1 | tee -a "$OUT"
done
if grep -q "counters: perf_event_open" "$OUT"; then
  if command -v simpleperf >/dev/null 2>&1; then STAT="simpleperf stat -e instructions:u,cpu-cycles:u"
  elif command -v perf >/dev/null 2>&1; then STAT="perf stat -x, -e instructions:u,cycles:u"
  else STAT=""; fi
  if [ -n "$STAT" ]; then
    for c in pow lzx biquad walk physics; do
      for v in base novol noswap noflush nolr inline locals allswap all norestrict fixed pin pinsep native; do
        [ "$c" = physics ] && [ "$v" = native ] && continue
        case "$(uname -m)-$v" in aarch64-*) ;; *-fixed|*-pin|*-pinsep) continue ;; esac
        echo "## stat $c $v" | tee -a "$OUT"
        $STAT ./nn08_bench --case "$c" --variant "$v" --solo --repeats 3 --ms 200 --cpu "$CPU" 2>&1 \
          | grep -E "instructions|cycles|^   $v " | tee -a "$OUT"
      done
    done
  fi
fi
echo "wrote $(pwd)/$OUT"
