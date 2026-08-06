#!/usr/bin/env bash
# Shared helpers for the cross-variant scripts on `main`. Sourced, never executed.
#
# The model: `main` carries no application code that anybody benchmarks. It carries the
# registry (variants.env) and the orchestration. Each variant is built and run from its own
# git worktree, using that branch's OWN harness — its docker-compose.yml, prometheus.yml,
# queries.promql and evaluate.py. That is deliberate: the TO and ES families genuinely
# differ (service names api-to/api-es, job labels, saga queries that exist on one side
# only), and re-implementing a unified version on `main` would mean maintaining a third
# copy that drifts from both.

# Two different roots, and conflating them nests worktrees inside worktrees.
#
# MAIN_ROOT is where THIS checkout of `main` lives — the directory holding variants.env and
# scripts/. Resolved from BASH_SOURCE so the scripts work from any cwd.
#
# REPO_ROOT is the PRIMARY working tree, derived from git's common dir. That is the anchor
# for .worktrees/ and bench-results/, and it has to be, because `main` may itself be checked
# out in a worktree. Anchoring on MAIN_ROOT in that case would put the variant worktrees at
# .worktrees/main/.worktrees/<variant> and scatter artifacts to match. With this split, the
# layout is identical whether main is the primary checkout or just another worktree.
MAIN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$(git -C "$MAIN_ROOT" rev-parse --git-common-dir)/.." && pwd)"
WORKTREE_DIR="$REPO_ROOT/.worktrees"
RESULTS_DIR="$REPO_ROOT/bench-results"

# One Compose project for every variant, rather than the per-directory default.
#
# Without this each worktree gets its own project name from its directory basename, so
# `down` in one project cannot remove containers left by another — and since all eight
# variants publish the same host ports (8080 nginx, 9090 prometheus, 3000 grafana, 5432
# postgres), a leftover stack from the previous variant fails the next one with nothing but
# a port conflict or a health timeout to go on. With a single project name, one
# `down -v --remove-orphans` provably clears whatever ran last, including across a TO->ES
# switch where even the service names differ (--remove-orphans is what catches those).
#
# Container names become iir-api-es-1 etc. That is fine: API_CONTAINER_RE is `.*api-es.*`,
# unanchored, and every cadvisor query in queries.promql matches on it.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-iir}"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] FATAL: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# ---------------------------------------------------------------- registry

# Emits "<variant> <branch> <family> <capabilities>" per line, comments and blanks stripped.
# Capabilities default to "-" so a registry line written before that column existed still
# parses rather than yielding an empty field.
read_variants() {
    local reg="$MAIN_ROOT/variants.env"
    [ -f "$reg" ] || die "variants.env not found at $reg"
    sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$reg" \
        | awk '{print $1, $2, $3, ($4 == "" ? "-" : $4)}'
}

branch_of() { read_variants | awk -v v="$1" '$1 == v {print $2}'; }
family_of() { read_variants | awk -v v="$1" '$1 == v {print $3}'; }
caps_of()   { read_variants | awk -v v="$1" '$1 == v {print $4}'; }

has_cap() {
    case ",$(caps_of "$1")," in *",$2,"*) return 0 ;; *) return 1 ;; esac
}

# Warn when a knob is set for variants whose branch does not implement it.
#
# This failure is silent by construction and cannot be caught downstream: k6 is
# byte-identical on all eight branches and sends every knob, Spring ignores unknown JSON
# properties, and meta.json records what k6 was TOLD rather than what the server honoured.
# So a suite-wide RESERVE_DELAY_MS sweep produces six rows that look like they applied a
# delay and did not. Nothing else in the pipeline can tell the difference, which is why the
# check has to live here, before the first run starts.
warn_unsupported_knobs() {
    local variants="$1"
    _warn_knob "$variants" RESERVE_DELAY_MS "${RESERVE_DELAY_MS:-}" reserve-delay \
        "a per-reserve sleep in the aggregate" "ES-4,TO-3"
    _warn_knob "$variants" PAYLOAD_BYTES "${PAYLOAD_BYTES:-}" payload-bytes \
        "aggregate padding on the row each reserve rewrites" "ES-1,ES-2,ES-3,ES-4,TO-3"
}

_warn_knob() {
    local variants="$1" name="$2" value="$3" cap="$4" what="$5" ok="$6" v missing=""
    [ -n "$value" ] && [ "$value" != "0" ] || return 0
    for v in $variants; do
        has_cap "$v" "$cap" || missing="$missing $v"
    done
    [ -n "$missing" ] || return 0
    log ""
    log "WARNING: $name=$value is $what, and these selected variants do not implement it:"
    log "        $missing"
    log "         k6 sends the field to every branch and Spring ignores unknown properties, so"
    log "         it is discarded there with no error, while meta.json still records $value —"
    log "         it records what k6 was told, not what the server applied."
    log "         Branches that honour it: $ok"
    log ""
}

# Resolve a --only/-o list against the registry, preserving REGISTRY order (not the order
# the user typed) so a suite pass always crosses the family boundary once.
select_variants() {
    local only="$1" all v
    all="$(read_variants | awk '{print $1}')"
    if [ -z "$only" ]; then echo "$all"; return; fi
    # Validate every name first: a typo must fail loudly, not silently run a subset.
    for v in ${only//,/ }; do
        grep -qx -- "$v" <<<"$all" || die "unknown variant '$v' (known: $(tr '\n' ' ' <<<"$all"))"
    done
    # An explicit `if`, not `grep ... && echo`. Under `set -e` the && list's non-zero status
    # on a non-selected variant aborts the whole function — which silently returned a short
    # list, or none at all, instead of the requested subset.
    for v in $all; do
        if grep -qx -- "$v" <<<"${only//,/$'\n'}"; then echo "$v"; fi
    done
}

image_tag() { printf 'inventory-reservation-%s:latest\n' "$(tr 'A-Z' 'a-z' <<<"$1")"; }

# ---------------------------------------------------------------- worktrees

# Where a variant's tree lives. Normally .worktrees/<variant>, but if that branch happens
# to be the one checked out in the repo root, the root IS its working tree and git refuses
# to check it out a second time. Handling that here means the scripts work whatever branch
# the user left the root on, rather than demanding they switch to main first.
worktree_path() {
    local variant="$1" branch
    branch="$(branch_of "$variant")"
    [ -n "$branch" ] || die "variant '$variant' is not in variants.env"
    if [ "$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)" = "$branch" ]; then
        echo "$REPO_ROOT"
    else
        echo "$WORKTREE_DIR/$variant"
    fi
}

ensure_worktree() {
    local variant="$1" branch wt
    branch="$(branch_of "$variant")"
    wt="$(worktree_path "$variant")"

    if [ ! -d "$wt" ]; then
        log "worktree: creating $wt for $branch"
        git -C "$REPO_ROOT" worktree add "$wt" "$branch" >/dev/null \
            || die "git worktree add failed for $branch"
    fi

    # Nothing here fetches, pulls or checks out. The branches are local and the user owns
    # pushing; silently moving a worktree to a different commit would change what is being
    # measured without saying so.

    # Central artifacts. bench.sh writes to <its repo root>/bench-results, and the k6
    # service bind-mounts ./bench-results from the same directory, so pointing the
    # worktree's copy at main's makes every variant's run land in one place. Docker
    # resolves the symlink host-side, so the bind mount follows it.
    mkdir -p "$RESULTS_DIR"
    if [ "$wt" != "$REPO_ROOT" ] && [ ! -L "$wt/bench-results" ]; then
        if [ -d "$wt/bench-results" ]; then
            die "$wt/bench-results is a real directory; move it aside so it can be linked to $RESULTS_DIR"
        fi
        ln -s "$RESULTS_DIR" "$wt/bench-results"
        log "worktree: $variant/bench-results -> $RESULTS_DIR"
    fi

    echo "$wt"
}

# ---------------------------------------------------------------- docker

# Compose invocation for one variant, as a function over an array — a string variable here
# would depend on unquoted word-splitting, which breaks on any path containing a space.
dc_for() {
    local wt="$1"; shift
    docker compose -f "$wt/docker-compose.yml" -f "$wt/docker-compose.bench.yml" "$@"
}

# Full stop. -v is required, not tidiness: reset.sh only truncates tables, so without it a
# TO run would inherit the previous ES run's postgres volume under a schema that does not
# match. --remove-orphans is what clears the other family's containers, which are simply
# unknown services from this compose file's point of view.
#
# This also fixes the Prometheus bind-mount staleness: prometheus.yml differs per family
# (job label inventory-to vs inventory-es) and editing the file never reaches a running
# container. `down` removes the container, so the next `up` provably re-reads it.
teardown() {
    local wt="$1"
    [ -d "$wt" ] || return 0
    dc_for "$wt" down -v --remove-orphans --timeout 30 >/dev/null 2>&1 || true
}

# `down --remove-orphans` only reaches containers in OUR compose project. A stack brought
# up by hand from the repo root, or by an older workflow, lives under a different project
# name (Compose defaults to the directory basename) and keeps its grip on the shared host
# ports. The failure that produces is a port-binding error or a health timeout several
# minutes into a run, which says nothing about the actual cause — so name it up front.
assert_ports_free() {
    local foreign
    foreign="$(docker ps --format '{{.Names}}\t{{.Label "com.docker.compose.project"}}\t{{.Ports}}' \
        | awk -F'\t' -v proj="$COMPOSE_PROJECT_NAME" \
              '$2 != proj && $3 ~ /0\.0\.0\.0:(8080|9090|3000|5432)->/ {print "    " $1 " (project: " ($2 == "" ? "none" : $2) ")"}')"
    [ -z "$foreign" ] && return 0
    printf 'FATAL: containers outside the "%s" compose project are holding the ports the suite needs:\n%s\n' \
        "$COMPOSE_PROJECT_NAME" "$foreign" >&2
    printf 'Stop them first. From the directory that started them:\n    docker compose down\n' >&2
    printf 'Add -v as well only if you also want their volumes erased.\n' >&2
    exit 1
}

require_tools() {
    local t
    for t in docker python3 curl git; do
        command -v "$t" >/dev/null || die "required tool not on PATH: $t"
    done
    docker info >/dev/null 2>&1 \
        || die "cannot reach the docker daemon (running? are you in the docker group?)"
}

# Running under sudo makes bench-results/ root-owned, which breaks every later run. Same
# guard bench.sh applies; repeated here so it fires before any work is done.
require_not_root() {
    [ "$(id -u)" != "0" ] || [ "${ALLOW_ROOT:-0}" = "1" ] \
        || die "do not run this under sudo — it makes bench-results/ root-owned. Add yourself to the docker group instead (ALLOW_ROOT=1 overrides)."
}
