#!/usr/bin/env bash
# Startup budget (02-governance §6): time to first prompt with the fake provider must be <= 800ms.
set -euo pipefail; cd "$(dirname "$0")/.."
jar=$(ls pai-cli/target/pai-cli-*.jar | head -1)
start=$(date +%s%3N); echo "/quit" | java -jar "$jar" --provider fake >/dev/null; end=$(date +%s%3N)
ms=$((end-start)); echo "startup+quit = ${ms}ms"; [ "$ms" -le 800 ] || { echo "::error::startup budget exceeded"; exit 1; }
