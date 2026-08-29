#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/riftengine/config/pins.env"

CACHE_ROOT="${RIFTENGINE_CACHE_ROOT:-$ROOT/.riftengine-cache}"
WORK_ROOT="${RIFTENGINE_WORK_ROOT:-$ROOT/.riftengine-work}"
PROFILE="${RIFTENGINE_PROFILE:-release-single}"
PORT_SCRIPT="$ROOT/riftengine/tools/port-jsc-emscripten.py"
CMAKE_DIR="$ROOT/riftengine/cmake"

hash_path() {
  local path="$1"
  if [ -f "$path" ]; then
    sha256sum "$path" | awk '{print $1}'
  elif [ -d "$path" ]; then
    find "$path" -type f -print0 \
      | sort -z \
      | xargs -0 sha256sum \
      | sha256sum \
      | awk '{print $1}'
  else
    printf 'missing'
  fi
}

PORT_HASH="$(hash_path "$PORT_SCRIPT")"
CMAKE_HASH="$(hash_path "$CMAKE_DIR")"
KEY_INPUT="webkit=${RIFT_WEBKIT_COMMIT};emsdk=${RIFT_EMSDK_VERSION};icu=${RIFT_ICU_RELEASE};profile=$PROFILE;port=$PORT_HASH;cmake=$CMAKE_HASH"
CORE_KEY="$(printf '%s' "$KEY_INPUT" | sha256sum | awk '{print $1}')"
CORE_KEY_SHORT="${CORE_KEY:0:20}"

CORE_DIR="$CACHE_ROOT/jsc-core/$CORE_KEY_SHORT"
HOST_DIR="$WORK_ROOT/host/$PROFILE"
WEBCORE_DIR="$WORK_ROOT/webcore/$PROFILE"
DIST_DIR="$ROOT/riftengine/dist/$PROFILE"

mkdir -p "$CORE_DIR" "$HOST_DIR" "$WEBCORE_DIR" "$DIST_DIR"

cat > "$CORE_DIR/BUILD-KEY.txt" <<EOF
RIFTENGINE_CORE_KEY=$CORE_KEY
RIFTENGINE_CORE_KEY_SHORT=$CORE_KEY_SHORT
RIFTENGINE_PROFILE=$PROFILE
RIFTENGINE_KEY_INPUT=$KEY_INPUT
EOF

cat > "$WORK_ROOT/layout.env" <<EOF
export RIFTENGINE_CORE_KEY='$CORE_KEY'
export RIFTENGINE_CORE_KEY_SHORT='$CORE_KEY_SHORT'
export RIFTENGINE_CORE_DIR='$CORE_DIR'
export RIFTENGINE_HOST_DIR='$HOST_DIR'
export RIFTENGINE_WEBCORE_DIR='$WEBCORE_DIR'
export RIFTENGINE_DIST_DIR='$DIST_DIR'
export RIFTENGINE_PROFILE='$PROFILE'
EOF

printf '%s\n' \
  "RIFTENGINE_BUILD_LAYOUT=ready" \
  "RIFTENGINE_PROFILE=$PROFILE" \
  "RIFTENGINE_CORE_KEY=$CORE_KEY_SHORT" \
  "RIFTENGINE_CORE_DIR=$CORE_DIR" \
  "RIFTENGINE_HOST_DIR=$HOST_DIR" \
  "RIFTENGINE_WEBCORE_DIR=$WEBCORE_DIR" \
  "RIFTENGINE_DIST_DIR=$DIST_DIR"
