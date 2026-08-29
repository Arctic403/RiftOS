#!/usr/bin/env bash
set -euo pipefail

RIFTOS_ROOT="${RIFTOS_ROOT:-/workspaces/RiftOS}"
ROOT="${RIFTENGINE_ROOT:-/workspaces/.riftengine/WebkitWasm}"
source "$RIFTOS_ROOT/scripts/riftengine/version.env"

sudo apt-get update
sudo apt-get install -y \
  build-essential git curl cmake ninja-build make python3 pkg-config \
  tar xz-utils unzip clang ripgrep gperf autoconf automake libtool gettext

mkdir -p "$(dirname "$ROOT")"
if [ ! -d "$ROOT/.git" ]; then
  git clone --filter=blob:none --no-checkout "$RIFTENGINE_UPSTREAM_REPO" "$ROOT"
fi

# Do not build a moving branch head. The audit/refactor is tied to this exact
# upstream commit so tomorrow's compile is reproducible.
git -C "$ROOT" fetch origin "$RIFTENGINE_UPSTREAM_COMMIT" --depth 1
git -C "$ROOT" checkout --detach "$RIFTENGINE_UPSTREAM_COMMIT"
git -C "$ROOT" reset --hard "$RIFTENGINE_UPSTREAM_COMMIT"

python3 "$RIFTOS_ROOT/scripts/riftengine/apply-upstream-patches.py" "$ROOT"

printf '\nRiftEngine Codespace ready.\n'
printf 'Upstream: %s\n' "$(git -C "$ROOT" rev-parse HEAD)"
printf 'Profile:  %s (pthread=%s)\n' "$RIFTENGINE_PROFILE" "$RIFTENGINE_BIB_PTHREAD"
printf 'Root:     %s\n' "$ROOT"
printf 'Next:     bash scripts/riftengine-codespace-build.sh\n'
