#!/usr/bin/env bash
set -euo pipefail

version=1.7.12
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) platform=linux_amd64; checksum=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 ;;
  Darwin-arm64) platform=darwin_arm64; checksum=aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f ;;
  *) echo 'Pinned actionlint supports Linux x86_64 and macOS arm64; use check.yml on other hosts.' >&2; exit 1 ;;
esac
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
archive="$scratch/actionlint.tar.gz"
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  "https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_${platform}.tar.gz" -o "$archive"
echo "$checksum  $archive" | shasum -a 256 --check
tar -xzf "$archive" -C "$scratch" actionlint

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
files=()
for directory in .github/workflows examples/quickstart/.github/workflows; do
  [[ -d "$directory" ]] || continue
  while IFS= read -r file; do files+=("$file"); done < <(find "$directory" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
done
files+=("examples/quickstart/.github/workflows/stale.yml.example")
# These GitHub fields are newer than actionlint's schema. Kotlin policy checks enforce them.
"$scratch/actionlint" -ignore 'unexpected key "queue"' \
  -ignore 'property "workflow_(sha|repository)" is not defined' "${files[@]}"
