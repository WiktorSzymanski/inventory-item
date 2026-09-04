#!/usr/bin/env bash
# Shared helpers for the cross-variant scripts on `main`. Sourced, never executed.
#
# The model: `main` carries no application code that anybody benchmarks. It carries the
# registry (variants.env), the one shared harness (k6/, docker-compose.yml) and the
# orchestration. Building a variant still needs its branch — `ensure_worktree` checks out
# `.worktrees/<variant>/` so `build-images.sh` can build that branch's `Dockerfile` and
# `src/` — but running one does not: `run-suite.sh` runs `main`'s own harness against the
# image that step produced, from `main`'s own working tree. The families' schemas and saga
# queries still differ; what turned out to be reconcilable was everything the harness
# itself touches — service names, job labels, the datasource URL — which `main` now names
# uniformly (`api`, `postgres`) instead of keeping eight copies of `k6/` in step by hand.

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
# `down` in one project cannot remove containers left by another — and since every
# variant publishes the same host ports (8080 api, 9090 prometheus, 3000 grafana, 5432
# postgres), a leftover stack from the previous variant fails the next one with nothing but
# a port conflict or a health timeout to go on. With a single project name, one
# `down -v --remove-orphans` provably clears whatever ran last, including across a TO->ES
# switch where even the service names differ (--remove-orphans is what catches those).
#
# The project name does not leak into container names: every service in docker-compose.yml,
# the api included, pins its own container_name. So cadvisor reports a plain `api`,
# k6/bench/common.sh sets API_CONTAINER_RE to exactly that, and every cadvisor query in
# queries.promql matches on it regardless of what this project is called.
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
# the same for every variant and sends every knob, Spring ignores unknown JSON
# properties, and meta.json records what k6 was TOLD rather than what the server honoured.
# The compose-forwarded knobs (SNAPSHOT_*) fail the same way one layer over: compose passes
# every variable it names to every variant's container, and a branch that never declares the
# property binds nothing.
# So a suite-wide RESERVE_DELAY_MS sweep produces six rows that look like they applied a
# delay and did not. Nothing else in the pipeline can tell the difference, which is why the
# check has to live here, before the first run starts.
_SNAPSHOT_KNOB_HOW="compose forwards the variable to every container and only a branch with
SnapshotProperties binds it, so elsewhere it is discarded with no error, while"

warn_unsupported_knobs() {
    local variants="$1"
    _warn_knob "$variants" RESERVE_DELAY_MS "${RESERVE_DELAY_MS:-}" reserve-delay \
        "a per-reserve sleep in the aggregate"
    _warn_knob "$variants" PAYLOAD_BYTES "${PAYLOAD_BYTES:-}" payload-bytes \
        "aggregate padding on the row each reserve rewrites"
    # Not a k6 knob: compose forwards these to the container, so the sixth argument replaces the
    # k6 sentence in _warn_knob with how THIS one goes missing. Same shape of failure either way.
    _warn_knob "$variants" SNAPSHOT_ENABLED "${SNAPSHOT_ENABLED:-}" snapshot-trigger \
        "the aggregate snapshot switch" "$_SNAPSHOT_KNOB_HOW"
    _warn_knob "$variants" SNAPSHOT_EVENT_COUNT "${SNAPSHOT_EVENT_COUNT:-}" snapshot-trigger \
        "the aggregate snapshot interval" "$_SNAPSHOT_KNOB_HOW"
}

_warn_knob() {
    local variants="$1" name="$2" value="$3" cap="$4" what="$5" how="${6:-}" v missing="" ok=""
    [ -n "$value" ] && [ "$value" != "0" ] || return 0
    for v in $variants; do
        has_cap "$v" "$cap" || missing="$missing $v"
    done
    [ -n "$missing" ] || return 0
    # Derived from the registry, never hardcoded: the supported set moves as branches gain
    # the knob, and a stale literal here would misdirect exactly when the warning matters.
    for v in $(read_variants | awk '{print $1}'); do
        has_cap "$v" "$cap" && ok="$ok $v"
    done
    ok="${ok# }"; ok="${ok// /, }"
    log ""
    log "WARNING: $name=$value is $what, and these selected variants do not implement it:"
    log "        $missing"
    # The default sentence is k6's transport. A caller whose knob travels another way passes its
    # own; the tail is common, because what makes the failure silent is the same in both cases —
    # meta.json records the request, and nothing downstream can tell a honoured knob from a
    # dropped one.
    if [ -n "$how" ]; then
        while IFS= read -r line; do log "         $line"; done <<<"$how"
    else
        log "         k6 sends the field to every branch and Spring ignores unknown properties, so"
        log "         it is discarded there with no error, while"
    fi
    log "         meta.json still records $value — it records what the harness was told, not"
    log "         what the server applied."
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

# Make $MAIN_ROOT/bench-results resolve to the central RESULTS_DIR.
#
# A no-op when main is the primary checkout (same directory). When main is a worktree the
# two diverge, and without the link bench.sh writes its run somewhere run-suite.sh never
# looks. Docker resolves the symlink host-side, so docker-compose.bench.yml's
# `./bench-results` bind mount follows it too.
ensure_results_link() {
    mkdir -p "$RESULTS_DIR"
    [ "$MAIN_ROOT" = "$REPO_ROOT" ] && return 0
    local local_dir="$MAIN_ROOT/bench-results"
    [ -L "$local_dir" ] && return 0
    if [ -d "$local_dir" ]; then
        rmdir "$local_dir" 2>/dev/null || die \
            "$local_dir is a real directory with contents; move it aside so it can be linked to $RESULTS_DIR"
    fi
    ln -s "$RESULTS_DIR" "$local_dir"
    log "bench-results -> $RESULTS_DIR"
}

# ---------------------------------------------------------------- docker

# Compose invocation for the ONE stack main owns. Previously this took a worktree path,
# because each branch had its own compose file; there is now a single file.
dc_main() {
    docker compose -f "$MAIN_ROOT/docker-compose.yml" \
                   -f "$MAIN_ROOT/docker-compose.bench.yml" "$@"
}

# Full stop between variants. -v is required, not tidiness: reset.sh only truncates tables,
# so without it a TO run would inherit the previous ES run's postgres volume under a schema
# that does not match. --remove-orphans clears anything left by an older layout whose
# service names differed.
#
# `--profile netem` is what reaches the delay sidecar. `down` does NOT remove the containers of
# services whose profile is inactive — verified against Compose v5.5.0: a plain
# `down -v --remove-orphans` tore the whole stack down and left `netem` sitting there exited.
# The qdisc itself dies with postgres' network namespace either way, so what this prevents is
# only a stale container, but bench.sh then has to notice and clear it on the next unshaped run
# rather than starting from nothing. Add any future profile here for the same reason.
teardown() {
    dc_main --profile netem down -v --remove-orphans --timeout 30 >/dev/null 2>&1 || true
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

# ---------------------------------------------------------------- workload points

POINT_IDENTITY_KNOBS="DISTINCT_ITEMS ITEMS_PER_ORDER PAYLOAD_BYTES RESERVE_DELAY_MS"
POINT_CALIBRATION_KNOBS="STEP_START STEP_INC STEP_COUNT WARMUP_RATE"

# Capture what the SHELL set, before any point expands. Afterwards a point-set knob and a
# shell-set knob are indistinguishable, so the conflict check would have nothing to compare
# against. Must be called before resolve_point.
snapshot_shell_knobs() {
    local k
    for k in $POINT_IDENTITY_KNOBS $POINT_CALIBRATION_KNOBS; do
        eval "__SHELL_$k=\"\${$k:-}\""
    done
}

# Emit "KEY=VALUE" per line for one named point.
read_point() {
    local p="$1" fields
    fields="$(sed -e 's/#.*//' "$MAIN_ROOT/points.env" \
              | awk -v p="$p" '$1 == p { for (i = 2; i <= NF; i++) print $i }')"
    [ -n "$fields" ] || die "unknown point '$p' (known: $(known_points | tr '\n' ' '))"
    printf '%s\n' "$fields"
}

known_points() {
    sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$MAIN_ROOT/points.env" | awk '{print $1}'
}

# Expand $POINT (comma-separated, composable) into the workload knobs and RUN_LABEL.
#
# TWO conflicts have to be caught here, not one. The shell-vs-point check below compares
# against __SHELL_$key, captured before anything expanded — but that is empty for a knob no
# point has touched yet, so once point A set an identity knob, point B saw an empty prior and
# silently overwrote it. Measured before the fix:
#
#     POINT=C01,C11      -> PAYLOAD_BYTES=1048576  label C01-C11   rc=0
#     POINT=W-base,W-hot -> DISTINCT_ITEMS=8       label W-base-W-hot  rc=0
#
# i.e. a typo (POINT=C01,C11 for the intended POINT=W-base,C11) produced a run labelled
# C01-C11 while only C11's knobs applied — precisely the mislabelled run points.env exists to
# make impossible. So identity knobs also carry the point that set them, and a LATER point
# offering a DIFFERENT value is fatal. The same value is fine, and composition of points that
# do not overlap (POINT=W-base,C11, a documented campaign command) is untouched.
resolve_point() {
    [ -n "${POINT:-}" ] || return 0
    local p kv key val prior label="" owner owner_val
    # Clear any residue, so a second resolve_point in one shell cannot inherit the first's
    # ownership records and reject a legitimate re-resolution.
    for key in $POINT_IDENTITY_KNOBS; do
        unset "__POINT_OWNER_$key" "__POINT_VALUE_$key"
    done
    for p in ${POINT//,/ }; do
        # Checked here, not left to read_point's own die(): that call runs inside the
        # < <(...) process substitution below, a subshell whose exit status this loop
        # never inspects, so a die() there would be silently swallowed and the run would
        # proceed as if the point matched nothing.
        case " $(known_points | tr '\n' ' ') " in
            *" $p "*) ;;
            *) die "unknown point '$p' (known: $(known_points | tr '\n' ' '))" ;;
        esac
        label="${label:+$label-}$p"
        while IFS= read -r kv; do
            [ -n "$kv" ] || continue
            key="${kv%%=*}"; val="${kv#*=}"
            eval "prior=\"\${__SHELL_$key:-}\""
            case " $POINT_IDENTITY_KNOBS " in
                *" $key "*)
                    if [ -n "$prior" ] && [ "$prior" != "$val" ]; then
                        die "POINT=$p defines $key=$val but the environment sets $key=$prior. $key is an identity knob: honouring the override would make the run label '$label' describe a workload it did not run. Drop the override, or drop POINT and set every knob by hand."
                    fi
                    eval "owner=\"\${__POINT_OWNER_$key:-}\"; owner_val=\"\${__POINT_VALUE_$key:-}\""
                    if [ -n "$owner" ] && [ "$owner_val" != "$val" ]; then
                        die "POINT=$POINT composes '$owner' and '$p', which disagree on $key ('$owner' sets $key=$owner_val, '$p' sets $key=$val). $key is an identity knob, so the later value would win while the run label '$label' still named both points — the run would be labelled for a workload it did not run. Compose points that do not overlap, or name a single point."
                    fi
                    eval "__POINT_OWNER_$key=\"\$p\"; __POINT_VALUE_$key=\"\$val\""
                    export "$key=$val" ;;
                *)
                    [ -n "$prior" ] || export "$key=$val" ;;
            esac
        done < <(read_point "$p")
    done
    POINT_RESOLVED="$label"
    RUN_LABEL="${RUN_LABEL:-$label}"
    export POINT_RESOLVED RUN_LABEL
}
