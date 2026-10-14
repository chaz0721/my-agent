#!/usr/bin/env bash
# Dump public/protected API of a module to api/<module>.api (02-governance §3).
# Usage: build/dump-api.sh pai-core   (requires `mvn -q compile` first)
set -euo pipefail
cd "$(dirname "$0")/.."; m=$1; out=api/$m.api; mkdir -p api
classes=$m/target/classes
find "$classes" -name '*.class' | grep -v '/internal/' | sed "s|$classes/||; s|\.class$||; s|/|.|g" | sort | while read -r c; do
  javap -protected -cp "$classes" "$c" 2>/dev/null | grep -vE '^\s*$|^Compiled from' | sed 's/ *{$//; s/^  //'
done > "$out"
echo "wrote $out ($(wc -l < "$out") lines)"
