#!/bin/sh
# NN-0.8 bench on the Steam Deck over SSH (run from the repository root).
# The Deck's address is DHCP: set DECK=deck@<ip> if it moved from 192.168.1.81.
set -e
DECK=${DECK:-deck@192.168.1.81}
KEY=${DECK_KEY:-C:/Users/neri/AppData/Local/steamos-devkit/steamos-devkit/devkit_rsa}
SSH="ssh -i $KEY -o IdentitiesOnly=yes"
$SSH "$DECK" mkdir -p nn08
scp -i "$KEY" -o IdentitiesOnly=yes .local/nn08-bench/bin/linux/nn08_bench tools/nn08-bench/run-device.sh "$DECK":nn08/
scp -i "$KEY" -o IdentitiesOnly=yes .local/nn08-bench/bin/linux-v3/nn08_bench "$DECK":nn08/nn08_bench_v3
$SSH "$DECK" 'chmod 755 nn08/nn08_bench nn08/nn08_bench_v3 nn08/run-device.sh && nn08/run-device.sh 2'
mkdir -p .local/nn08-bench/results
scp -i "$KEY" -o IdentitiesOnly=yes "$DECK:nn08/nn08-*.txt" .local/nn08-bench/results/
