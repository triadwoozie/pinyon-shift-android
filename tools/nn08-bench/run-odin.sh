#!/bin/sh
# NN-0.8 bench on the Odin 2 Portal over adb (run from the repository root).
# Pushes the Android build and runs it pinned to the prime core; fetches the log.
set -e
SERIAL=${ODIN_SERIAL:-192.168.1.198:41569}
BIN=.local/nn08-bench/bin/android/nn08_bench
export MSYS_NO_PATHCONV=1
adb -s "$SERIAL" shell mkdir -p /data/local/tmp/nn08
adb -s "$SERIAL" push "$BIN" tools/nn08-bench/run-device.sh /data/local/tmp/nn08/
adb -s "$SERIAL" shell chmod 755 /data/local/tmp/nn08/nn08_bench /data/local/tmp/nn08/run-device.sh
adb -s "$SERIAL" shell sh /data/local/tmp/nn08/run-device.sh 7
mkdir -p .local/nn08-bench/results
adb -s "$SERIAL" shell 'ls /data/local/tmp/nn08/nn08-*.txt' | tr -d '\r' | while read -r f; do
  adb -s "$SERIAL" pull "$f" .local/nn08-bench/results/
done
