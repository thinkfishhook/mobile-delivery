#!/usr/bin/env bash
set -euo pipefail

tool_root="${1:?Pass the mobile-delivery action repository root}"
runtime="${2:-$RUNNER_TEMP/mobile-delivery-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-$GITHUB_JOB}"
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java_home="$(python3 "$script_root/java-home.py" "$tool_root/src/main/resources/tool.json")"
key="$(bash "$script_root/cache-key.sh" "$tool_root")"
cache="$RUNNER_TEMP/mobile-delivery-engine/$key"
if [[ ! -f "$cache/.complete" || ! -x "$cache/bin/mobile-delivery" ]]; then
  JAVA_HOME="$java_home" "$tool_root/gradlew" -p "$tool_root" --no-daemon --quiet installDist
  mkdir -p "$cache"
  cp -R "$tool_root/build/install/mobile-delivery/." "$cache/"
  touch "$cache/.complete"
else
  echo "Using cached engine: $key"
fi
mkdir -p "$runtime"
cp -R "$cache/." "$runtime/"
# Invoke the engine's java directly. Its process and app-build subprocesses retain the
# caller's JAVA_HOME; exporting JAVA_HOME here would silently change KMP app builds.
{
  printf '#!/usr/bin/env bash\nset -euo pipefail\n'
  printf 'exec %q -classpath "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/*" delivery.MainKt "$@"\n' "$java_home/bin/java"
} > "$runtime/bin/mobile-delivery"
chmod +x "$runtime/bin/mobile-delivery"
echo "$runtime/bin" >> "$GITHUB_PATH"
