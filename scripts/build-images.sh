#!/usr/bin/env bash
# Build one distinct, provenance-stamped image per variant branch.
#
#   scripts/build-images.sh                       # all variants in variants.env
#   scripts/build-images.sh --only ES-4,TO-1      # a subset
#   scripts/build-images.sh --no-cache            # force a full rebuild
#
# Each branch is built from its own git worktree into inventory-reservation-<variant>:latest.
# Before this existed the tag was per FAMILY, so ES-2 and ES-4 overwrote each other and
# TO-1..TO-4 shared a single image — you could not hold two variants' images at once, which
# is the whole premise of a cross-variant suite.
#
# No host JDK is involved. The Dockerfile is a multi-stage build that runs gradle inside the
# builder image, so JAVA_HOME being broken on this machine is irrelevant here.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/lib.sh"

ONLY=""
NO_CACHE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --only|-o)  ONLY="$2"; shift 2 ;;
        --no-cache) NO_CACHE="--no-cache"; shift ;;
        -h|--help)  sed -n '2,16p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *)          die "unknown argument: $1" ;;
    esac
done

require_not_root
require_tools

VARIANTS="$(select_variants "$ONLY")"
mkdir -p "$RESULTS_DIR"
MANIFEST="$RESULTS_DIR/images.json"

build_one() {
    local variant="$1" wt tag sha base
    wt="$(ensure_worktree "$variant")"
    tag="$(image_tag "$variant")"
    sha="$(git -C "$wt" rev-parse HEAD)"

    [ -f "$wt/Dockerfile" ] || die "$variant: no Dockerfile in $wt"

    log "build: $variant ($(branch_of "$variant") @ ${sha:0:8}) -> $tag"

    # Stage 1: the real build.
    base="${tag%:latest}:src"
    docker build $NO_CACHE -q -t "$base" "$wt" >/dev/null \
        || die "$variant: docker build failed"

    # Stage 2: stamp the commit SHA on top, as a real layer.
    #
    # This is not cosmetic. evaluate.py treats `image_fresh` as a VALIDITY check, and
    # bench.sh computes it as `image Created >= HEAD commit time`. Docker's cache breaks
    # that: the Dockerfile only COPYs gradle/ and src/, so a commit touching just docs or
    # CLAUDE.md reuses every cached layer and the image keeps its ORIGINAL Created
    # timestamp — now older than HEAD — and a perfectly good run is reported INVALID.
    #
    # It must be RUN, not LABEL. A metadata-only instruction produces a new image ID but
    # BuildKit inherits `Created` from the base, so a LABEL stamp does not refresh the
    # timestamp at all (measured: label applied, Created unchanged). A RUN creates a layer,
    # and the image is then dated now. The SHA is in the instruction, so the cache key
    # changes exactly when the commit does — a new commit always yields a fresh Created,
    # and a re-run with no new commit is cached, but then HEAD has not moved either and the
    # comparison still holds. The LABEL rides along for machine-readable provenance.
    printf 'FROM %s\nRUN echo %s > /etc/image-revision\nLABEL org.opencontainers.image.revision=%s\n' \
           "$base" "$sha" "$sha" \
        | docker build -q -t "$tag" - >/dev/null \
        || die "$variant: provenance stamp failed"

    docker image inspect "$tag" --format \
        "  $variant  {{.Id}}  created {{.Created}}" >&2
}

for v in $VARIANTS; do
    build_one "$v"
done

# ---------------------------------------------------------------- manifest
# Records what was built, so run-suite.sh can tell whether an image still matches its
# branch head without rebuilding to find out.
python3 - "$MANIFEST" "$REPO_ROOT" "$MAIN_ROOT" $VARIANTS <<'PY'
import json, os, subprocess, sys, datetime

# root: the PRIMARY working tree, which anchors .worktrees/. main_root: this checkout of
# `main`, which holds variants.env. They differ whenever main is itself a worktree.
manifest_path, root, main_root, variants = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]

# Merge rather than overwrite: a --only build must not erase the other variants' records.
try:
    with open(manifest_path) as fh:
        data = json.load(fh)
except (OSError, ValueError):
    data = {}
data.setdefault("images", {})


def sh(*cmd):
    return subprocess.check_output(cmd, text=True).strip()


registry = {}
for line in open(os.path.join(main_root, "variants.env")):
    line = line.split("#", 1)[0].split()
    if len(line) == 3:
        registry[line[0]] = (line[1], line[2])

for v in variants:
    branch, family = registry[v]
    tag = f"inventory-reservation-{v.lower()}:latest"
    wt = root if sh("git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD") == branch \
        else os.path.join(root, ".worktrees", v)
    data["images"][v] = {
        "branch": branch,
        "family": family,
        "commit": sh("git", "-C", wt, "rev-parse", "HEAD"),
        "image_tag": tag,
        "image_id": sh("docker", "image", "inspect", tag, "--format", "{{.Id}}"),
        "image_created": sh("docker", "image", "inspect", tag, "--format", "{{.Created}}"),
        "built_at": datetime.datetime.now(datetime.timezone.utc)
                    .isoformat(timespec="seconds").replace("+00:00", "Z"),
    }

data["schema"] = 1
with open(manifest_path, "w") as fh:
    json.dump(data, fh, indent=2, sort_keys=True)
print(f"manifest -> {manifest_path}")
PY

log "done"
