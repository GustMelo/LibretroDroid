#!/usr/bin/env bash
set -euo pipefail

# Compares the vendored LibretroDroid fork with the exact upstream revision.
# It is intentionally read-only: updating upstream must never overwrite local
# patches by accident.

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/Swordfish90/LibretroDroid.git}"
UPSTREAM_REF="${UPSTREAM_REF:-8835c3098514390a271e36983957f7bb5f40abf1}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/libretrodroid-upstream.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

git clone -q --filter=blob:none --no-checkout "$UPSTREAM_URL" "$TMP/repo"
git -C "$TMP/repo" checkout -q "$UPSTREAM_REF"

python3 - "$ROOT" "$TMP/repo" <<'PY'
import os, sys
root, upstream = sys.argv[1:]
mappings = {
    "native/src": "libretrodroid/src/main/cpp",
    "libretrodroid/src/main/java": "libretrodroid/src/main/java",
}
for local_root, upstream_root in mappings.items():
    same = different = local_only = 0
    for directory, _, files in os.walk(os.path.join(root, local_root)):
        for name in files:
            local = os.path.join(directory, name)
            rel = os.path.relpath(local, os.path.join(root, local_root))
            remote = os.path.join(upstream, upstream_root, rel)
            if not os.path.isfile(remote):
                local_only += 1
            elif open(local, "rb").read() == open(remote, "rb").read():
                same += 1
            else:
                different += 1
    print(f"{local_root}: identical={same} modified={different} local-only={local_only}")
PY

echo "Upstream: $UPSTREAM_URL @ $UPSTREAM_REF"
echo "No files were changed. Review modified files before changing UPSTREAM_REF."
