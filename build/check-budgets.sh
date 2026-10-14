#!/usr/bin/env bash
# Line budgets + no TODO (02-governance §3). Change numbers here only.
set -euo pipefail; cd "$(dirname "$0")/.."; fail=0; red(){ echo "::error::$*"; fail=1; }
loc(){ find "$1/src/main/java" -name '*.java' -print0 2>/dev/null | xargs -0 cat 2>/dev/null | grep -cve '^\s*$' || true; }
for pair in pai-model:4000 pai-core:5000 pai-cli:1500; do m=${pair%%:*}; max=${pair##*:}; n=$(loc "$m"); echo "$m LOC=$n (max $max)"; [ "$n" -le "$max" ] || red "$m exceeds LOC budget"; done
while IFS= read -r -d '' f; do n=$(grep -cve '^\s*$' "$f"); [ "$n" -le 400 ] || red "$f: $n lines (max 400)"; done < <(find . -path '*/src/main/java/*' -name '*.java' -print0)
if grep -rnE 'TODO|FIXME|XXX' --include=*.java . ; then red "TODO/FIXME/XXX present"; fi
[ $fail -eq 0 ] && echo "budgets OK"; exit $fail
