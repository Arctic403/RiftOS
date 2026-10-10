#!/usr/bin/env bash
# Compile the external-only RiftOS ProbeV1 DEX; NEVER compile into RiftOS APK.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then echo "ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2; exit 2; fi
android_jar="$sdk/platforms/android-35/android.jar"
if [[ ! -f "$android_jar" ]]; then
  android_jar="$(find "$sdk/platforms" -maxdepth 2 -name android.jar | sort -V | tail -n 1)"
fi
[[ -f "$android_jar" ]] || { echo "Android platform android.jar missing" >&2; exit 2; }
d8="$(find "$sdk/build-tools" -maxdepth 2 -name d8 -type f | sort -V | tail -n 1)"
[[ -x "$d8" ]] || { echo "External Android D8 unavailable" >&2; exit 2; }
command -v javac >/dev/null || { echo "JDK javac unavailable" >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/stub-src/com/riftos/app" "$work/stub" "$work/classes" "$work/dex" "$here/out"
# Compile-only ABI stub. Deliberately NOT included in produced DEX.
cat > "$work/stub-src/com/riftos/app/RiftBootstrapEntry.java" <<'EOF'
package com.riftos.app;
import android.app.Application;
public interface RiftBootstrapEntry { void start(Application application); }
EOF
javac -source 8 -target 8 -cp "$android_jar" \
  -d "$work/stub" "$work/stub-src/com/riftos/app/RiftBootstrapEntry.java"
javac -source 8 -target 8 -cp "$android_jar:$work/stub" \
  -d "$work/classes" "$here/ProbeV1.java"
# Only the external probe class enters the dexer. Not the host ABI stub.
"$d8" --min-api 26 --lib "$android_jar" --classpath "$work/stub" \
  --output "$work/dex" "$work/classes/com/riftos/bootstrap/proof/ProbeV1.class"
test -s "$work/dex/classes.dex"
cp "$work/dex/classes.dex" "$here/out/probe-v1.dex"
( cd "$here" && sha256sum out/probe-v1.dex ) | tee "$here/out/probe-v1.sha256"
echo "External probe built separately: $here/out/probe-v1.dex"
