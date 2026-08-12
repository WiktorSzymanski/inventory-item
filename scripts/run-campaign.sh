#!/usr/bin/env bash
# Run several (scenario, workload point) steps in turn, each across every variant.
#
#   scripts/run-campaign.sh capacity:W-base capacity:W-hot capacity:W-fan
#   scripts/run-campaign.sh --dry-run soak:W-base:RATE=42
#   scripts/run-campaign.sh --resume bench-results/campaign-20260807T040000Z.state
#
# One step = one `run-suite.sh` invocation = every variant in variants.env on a clean stack.
# This script adds no knob vocabulary of its own; it decides WHICH steps run, in what order,
# and keeps enough state that a campaign interrupted at step 12 of 21 does not restart at
# step 1.
#
# STEP SYNTAX:  <scenario>[:<point>[:<KNOB>=<v>[,<KNOB>=<v>...]]]
#
#   capacity:W-base                       staircase at the W-base workload point
#   capacity:W-base,C11                   composed point (points.env composes with a comma)
#   soak:W-base:RATE=42,DRAIN_TIMEOUT=1800 per-step knobs
#   steady                                no point; knobs come from workload.env / defaults
#
# The knobs are per STEP rather than per campaign on purpose. Phase-2 rates are derived
# per cell from that cell's own knee, so a single campaign-wide RATE= would be wrong for
# every cell but one.
#
# WHY DERIVED RATES ABORT RATHER THAN DEFAULT (see --help output and the runbook §4.3/§6.3):
# `soak`, `stress` and `spike` are meant to run at a rate computed from a knee measured by
# an earlier `capacity` step — 0.6xK for soak, 1.25xK for stress, 0.4xK as the spike base.
# Nothing downstream can tell that a soak ran at k6's default 50/s instead: the run
# completes, the artifacts are well-formed, and the verdict may even be PASS. It is simply
# not the measurement the campaign asked for. So a soak/stress step without RATE, or a
# spike step without SPIKE_BASE, is refused BEFORE the first container starts rather than
# discovered eight hours later.
#
# WHAT THIS DELIBERATELY DOES NOT DO: derive those rates itself. Reading a knee off
# `compare.py --knee` and deciding whether the staircase bracketed it is a judgement call
# (see the runbook's bracketing rule) — automating it would bury exactly the decision the
# campaign design wants made deliberately.
#
# Options:
#   -n, --dry-run      validate the plan and print it; start nothing
#       --resume FILE  take the plan from a state file and skip finished steps
#       --retry-failed with --resume, re-run steps recorded FAIL (for INVALID re-runs)
#       --stop-on-fail abandon the campaign at the first failing step
#
# A step that has already produced a verdict is not re-run on --resume, FAIL included: for
# a benchmark a FAIL verdict is a result, not an error to retry until it passes. INVALID is
# the case the campaign says to re-run once (runbook §8.4), and run-suite.sh's exit code
# cannot distinguish it from FAIL — hence --retry-failed as the manual lever.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/lib.sh"

# Scenarios k6/lib/profiles.js can build, minus the internal seed/warmup phases that
# bench.sh runs itself. Kept in step with BUILDERS there; an unknown name is caught here
# rather than after a stack has been brought up.
KNOWN_SCENARIOS="capacity steady soak stress spike legacy legacy-vus"

# Scenarios whose rate is derived from a measured knee, and the knob that carries it.
RATE_DERIVED="soak stress spike"

rate_knob_for() {
    case "$1" in
        spike) echo "SPIKE_BASE" ;;   # profiles.js spike() reads spikeBase, not rate
        *)     echo "RATE" ;;
    esac
}

# The header block above, up to `set -euo pipefail`, IS the help text — one source, so the
# two cannot drift.
usage() { sed -n '2,/^set -euo pipefail$/p' "$0" | sed -e '$d' -e 's/^# \?//'; }

RESUME_FILE=""
DRY_RUN=0
STOP_ON_FAIL=0
RETRY_FAILED=0
STEPS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --resume)       RESUME_FILE="$2"; shift 2 ;;
        --dry-run|-n)   DRY_RUN=1; shift ;;
        --stop-on-fail) STOP_ON_FAIL=1; shift ;;
        --retry-failed) RETRY_FAILED=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        -*)             die "unknown option: $1" ;;
        *)              STEPS+=("$1"); shift ;;
    esac
done

# ---------------------------------------------------------------- step parsing

step_scenario() { printf '%s' "${1%%:*}"; }

# Field 2. Empty when the step is a bare scenario.
step_point() {
    local rest="${1#*:}"
    [ "$rest" = "$1" ] && { printf ''; return; }   # no colon at all
    printf '%s' "${rest%%:*}"
}

# Field 3 onward, as a space-separated KEY=VALUE list. Commas separate knobs; a value may
# not contain a comma (none of the harness knobs do).
step_knobs() {
    local rest="${1#*:}"
    [ "$rest" = "$1" ] && { printf ''; return; }
    local knobs="${rest#*:}"
    [ "$knobs" = "$rest" ] && { printf ''; return; }  # only two fields
    printf '%s' "${knobs//,/ }"
}

# ---------------------------------------------------------------- validation
#
# Everything here runs before any Docker work, so a typo in step 19 fails in the first
# second rather than after eighteen steps have burned a machine-day.
validate_step() {
    local spec="$1" scen point knobs k knob_needed found=0
    scen="$(step_scenario "$spec")"
    point="$(step_point "$spec")"
    knobs="$(step_knobs "$spec")"

    [ -n "$scen" ] || die "step '$spec': empty scenario"
    grep -qw -- "$scen" <<<"$KNOWN_SCENARIOS" \
        || die "step '$spec': unknown scenario '$scen' (known: $KNOWN_SCENARIOS)"

    # Points are validated against points.env by the same reader run-suite.sh uses, so a
    # name that passes here cannot fail later.
    if [ -n "$point" ]; then
        local p
        for p in ${point//,/ }; do
            grep -qx -- "$p" <<<"$(known_points)" \
                || die "step '$spec': unknown point '$p' (known: $(known_points | tr '\n' ' '))"
        done
    fi

    for k in $knobs; do
        case "$k" in
            *=*) ;;
            *) die "step '$spec': knob '$k' is not KEY=VALUE" ;;
        esac
    done

    # The derived-rate gate.
    #
    # Explicit `if`, never `[ ... ] && found=1`: under `set -e` that form's non-zero status
    # on the final iteration aborts the whole script, which here would look like a silent
    # validation pass. The same trap is documented in lib.sh's select_variants.
    if grep -qw -- "$scen" <<<"$RATE_DERIVED"; then
        knob_needed="$(rate_knob_for "$scen")"
        for k in $knobs; do
            if [ "${k%%=*}" = "$knob_needed" ]; then found=1; fi
        done
        # An inherited environment value counts: `RATE=42 scripts/run-campaign.sh soak:W-base`
        # is a legitimate way to run a single step.
        if [ -n "${!knob_needed:-}" ]; then found=1; fi
        [ "$found" = "1" ] || die \
"step '$spec': $scen needs an explicit $knob_needed.

  Its rate is derived from a knee measured by an earlier capacity step — runbook §4.3 and
  §6.3 give the multipliers (soak 0.6xK, stress 1.25xK, spike base 0.4xK). Without it the
  step would run at the harness default, complete normally, and produce artifacts that look
  valid but answer a different question.

  Read the knee first:   python3 k6/bench/compare.py --knee bench-results/*_capacity_*
  then add $knob_needed to this step's knobs, e.g.:

      ${scen}:${point}:${knobs:+${knobs// /,},}${knob_needed}=<value>"
    fi
}

# ---------------------------------------------------------------- state file
#
# Append-only. A step is done when a DONE line exists for it, so a crashed campaign leaves
# a file that still parses and the plan is recoverable from the STEP header lines.
state_plan()      { sed -n 's/^# STEP //p' "$1"; }
state_note()      { printf '%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$2" "$3" "${4:-}" >>"$1"; }

# >>> state-skip
# Latest recorded outcome for a step: DONE, FAIL, or empty if it never finished.
state_status() {
    grep -P "^[^\t]*\t(DONE|FAIL)\t\Q$2\E\t" "$1" 2>/dev/null | tail -1 | cut -f2 || true
}

# Whether a resumed campaign should skip this step.
#
# A DONE step is always skipped. A FAIL step is skipped too by default — it produced
# artifacts and a verdict, and for a benchmark a FAIL verdict is a RESULT (the variant
# missed its SLO), not an error to paper over by re-running until it passes. The exception
# the campaign design does call for is INVALID, which means the measurement itself was
# broken and must be re-run once (runbook §8.4); run-suite.sh cannot distinguish the two in
# its exit code, so --retry-failed is the manual lever for it.
state_should_skip() {
    case "$(state_status "$1" "$2")" in
        DONE) return 0 ;;
        FAIL) [ "$RETRY_FAILED" = "1" ] && return 1 || return 0 ;;
        *)    return 1 ;;
    esac
}
# <<< state-skip

if [ -n "$RESUME_FILE" ]; then
    [ -f "$RESUME_FILE" ] || die "no such state file: $RESUME_FILE"
    [ ${#STEPS[@]} -eq 0 ] || die "--resume takes the plan from the state file; do not also pass steps"
    mapfile -t STEPS < <(state_plan "$RESUME_FILE")
    [ ${#STEPS[@]} -gt 0 ] || die "$RESUME_FILE lists no steps (no '# STEP ' lines)"
    STATE="$RESUME_FILE"
    log "resuming $STATE (${#STEPS[@]} steps planned)"
fi

[ ${#STEPS[@]} -gt 0 ] || { usage; exit 2; }

for s in "${STEPS[@]}"; do validate_step "$s"; done

# ---------------------------------------------------------------- plan

echo
printf '  %-3s %-34s %s\n' '#' STEP KNOBS
printf '  %-3s %-34s %s\n' '-' ---- -----
i=0
for s in "${STEPS[@]}"; do
    i=$((i + 1))
    mark=""
    if [ -n "$RESUME_FILE" ]; then
        case "$(state_status "$STATE" "$s")" in
            DONE) mark="(done)" ;;
            FAIL) [ "$RETRY_FAILED" = "1" ] && mark="(failed, will retry)" || mark="(failed, skipping)" ;;
        esac
    fi
    printf '  %-3s %-34s %s %s\n' "$i" "$(step_scenario "$s"):$(step_point "$s")" \
        "$(step_knobs "$s")" "$mark"
done
echo

if [ "$DRY_RUN" = "1" ]; then
    log "dry run: plan is valid, nothing executed"
    exit 0
fi

require_not_root
require_tools
assert_ports_free
ensure_results_link

if [ -z "$RESUME_FILE" ]; then
    mkdir -p "$RESULTS_DIR"
    STATE="$RESULTS_DIR/campaign-$(date -u +%Y%m%dT%H%M%SZ).state"
    {
        printf '# campaign started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        for s in "${STEPS[@]}"; do printf '# STEP %s\n' "$s"; done
    } >"$STATE"
    log "state: $STATE"
fi

# ---------------------------------------------------------------- run

declare -A RESULT
CAMPAIGN_RC=0
i=0
for s in "${STEPS[@]}"; do
    i=$((i + 1))
    label="$(step_scenario "$s"):$(step_point "$s")"

    if state_should_skip "$STATE" "$s"; then
        st="$(state_status "$STATE" "$s")"
        log "[$i/${#STEPS[@]}] skip $label (already $st)"
        RESULT[$s]="skipped ($st)"
        continue
    fi

    scen="$(step_scenario "$s")"
    point="$(step_point "$s")"
    knobs="$(step_knobs "$s")"

    log ""
    log "########## [$i/${#STEPS[@]}] $label ${knobs:+($knobs)}"
    state_note "$STATE" START "$s"

    rc=0
    # --continue-on-fail is deliberate and not configurable per step: within a step, one
    # variant failing must not abandon the other seven. Campaign-level continuation is the
    # --stop-on-fail knob below.
    #
    # `env` with the step's knobs after SCENARIO/POINT so a step knob can override a
    # campaign-wide export, and so POINT's identity-conflict guard in run-suite.sh sees the
    # step's own values.
    env SCENARIO="$scen" ${point:+POINT="$point"} $knobs \
        "$HERE/run-suite.sh" --continue-on-fail || rc=$?

    if [ "$rc" = "0" ]; then
        RESULT[$s]="PASS"
        state_note "$STATE" DONE "$s" "rc=0"
    else
        RESULT[$s]="FAIL(rc=$rc)"
        CAMPAIGN_RC=1
        # Recorded as FAIL, and state_is_done treats FAIL as done: a resumed campaign does
        # not silently re-run a step that already produced its artifacts and a verdict.
        # Re-running it is a deliberate act — drop its line from the state file.
        state_note "$STATE" FAIL "$s" "rc=$rc"
        if [ "$STOP_ON_FAIL" = "1" ]; then
            log "step $label failed (rc=$rc) and --stop-on-fail is set"
            break
        fi
    fi
done

# ---------------------------------------------------------------- report

echo
printf '  %-34s %s\n' STEP RESULT
printf '  %-34s %s\n' ---- ------
for s in "${STEPS[@]}"; do
    printf '  %-34s %s\n' "$(step_scenario "$s"):$(step_point "$s")" "${RESULT[$s]:-not-run}"
done
echo
echo "  state:    $STATE"
echo "  resume:   scripts/run-campaign.sh --resume $STATE"
echo "  compare:  python3 k6/bench/compare.py --knee bench-results/*_capacity_*"
echo
exit "$CAMPAIGN_RC"
