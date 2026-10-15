#!/usr/bin/env bash
# Performance baseline (M7 7D) with the fake provider: no gate, only numbers (docs/reports/bench-M7.md).
# Usage: bash build/bench.sh [runs]   (runs of each end-to-end start, default 5)
set -euo pipefail; cd "$(dirname "$0")/.."
runs=${1:-5}; tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mvn -B -ntp -q package -DskipTests
mvn -B -ntp -q -pl pai-cli -am test -Dtest=BenchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpai.bench=1 -Dpai.bench.home="$tmp" -Dpai.bench.out="$tmp/bench.txt" >/dev/null
jar=pai-cli/target/pai-cli.jar; bench=$(ls -d "$tmp"/pai-bench-*); mkdir -p "$tmp/cold" "$tmp/coldcwd"
median() { sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'; }
start_ms() { # $1 = PAI_HOME, $2 = cwd, rest = extra args
  local h=$1 c=$2; shift 2; local s e
  s=$(date +%s%N); printf '/quit\n' | PAI_HOME="$h" java --enable-native-access=ALL-UNNAMED -jar "$jar" \
    --provider fake --cwd "$c" "$@" >/dev/null 2>&1; e=$(date +%s%N); echo $(( (e - s) / 1000000 ))
}
cold=$(for i in $(seq "$runs"); do start_ms "$tmp/cold" "$tmp/coldcwd"; done | median)
resume=$(for i in $(seq "$runs"); do start_ms "$bench/home" "$bench/proj" --resume; done | median)
echo "BENCH|cold-start-to-quit|$cold|ms|java -jar pai-cli.jar --provider fake, stdin '/quit', median of $runs"
echo "BENCH|resume-1000-entries-to-quit|$resume|ms|same with --resume of a 1000-entry session, median of $runs"
cat "$tmp/bench.txt"
