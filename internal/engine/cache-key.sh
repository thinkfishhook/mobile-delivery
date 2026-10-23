#!/usr/bin/env bash
set -euo pipefail

cd "${1:?Pass the mobile-delivery action repository root}"
system="${RUNNER_OS:-$(uname -s)}"
architecture="${RUNNER_ARCH:-$(uname -m)}"
[[ "$system-$architecture" =~ ^[A-Za-z0-9_-]+$ ]] || { echo 'Invalid runner OS or architecture' >&2; exit 1; }
source_hash="$(
  {
    for directory in src gradle internal/engine; do
      if [[ -d "$directory" ]]; then find "$directory" -type f -print0; fi
    done
    find . -maxdepth 1 -type f \( -name '*.kts' -o -name gradlew \) -print0
  } | LC_ALL=C sort -z | while IFS= read -r -d '' file; do
    printf '%s\0' "$file"
    shasum -a 256 < "$file"
  done | shasum -a 256 | cut -d ' ' -f 1
)"
printf 'mobile-delivery-%s-%s-%s\n' "$system" "$architecture" "$source_hash"
