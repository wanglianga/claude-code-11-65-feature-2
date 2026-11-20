#!/usr/bin/env bash
# End-to-end verification of the three point-in-time guarantees.
# Requires a running instance (default http://localhost:8080) that was seeded with demo data.
# Exits non-zero on the first failed assertion.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
JQ="${JQ:-jq}"
fail=0

need() { command -v "$1" >/dev/null || { echo "missing dependency: $1"; exit 2; }; }
need curl
need "$JQ"

say() { printf '\n=== %s\n' "$*"; }
ok()  { printf '  PASS  %s\n' "$*"; }
bad() { printf '  FAIL  %s\n' "$*"; fail=1; }
expect_eq() { # actual expected label
  if [ "$1" = "$2" ]; then ok "$3 ($1)"; else bad "$3: expected [$2] got [$1]"; fi
}

json() { curl -fsS "$@"; }

say "waiting for service"
for i in $(seq 1 60); do
  if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$BASE/actuator/health" >/dev/null || { echo "service is not reachable at $BASE"; exit 1; }

say "scenario index"
json "$BASE/api/demo/scenarios" | "$JQ" '{counts, scenario1LateScene:.scenario1LateScene.scene}'

# ---------------------------------------------------------------------------
say "scenario 1: late scene must not enter original scoring"
CASE="DROUGHT-2024-0001"
# Wait for the seeded original run to leave QUEUED.
for i in $(seq 1 30); do
  st=$(json "$BASE/api/cases/$CASE" | "$JQ" -r '.summary.status')
  [ "$st" = "SCORED" ] && break
  sleep 1
done
ST=$(json "$BASE/api/cases/$CASE" | "$JQ" -r '.summary.status')
case "$ST" in
  SCORED|PAID) ok "case status ($ST)";;
  *) bad "case status: expected SCORED/PAID got [$ST]";;
esac

ORIG=$(json "$BASE/api/cases/$CASE/trace" | "$JQ" -c '[.[]|select(.mode=="ORIGINAL")][0]')
RUN=$(echo "$ORIG" | "$JQ" -r '.id')
VERDICT=$(json "$BASE/api/runs/$RUN" | "$JQ" -r '.leakage.verdict')
case "$VERDICT" in PASS|PASS_WITH_EXCLUSIONS) ok "original verdict $VERDICT";; *) bad "original verdict $VERDICT";; esac

DETAIL=$(json "$BASE/api/runs/$RUN")
LATE=$(echo "$DETAIL" | "$JQ" -c '[.inputs[]|select(.sourceType=="SATELLITE_NDVI" and .eligibility=="EXCLUDED_LATE_AVAILABILITY")]|length')
[ "$LATE" -ge 1 ] && ok "late scene excluded ($LATE ledger entr(y/ies))" || bad "late scene was not excluded"
LATE_IN_FEATURES=$(echo "$DETAIL" | "$JQ" -r '[.inputs[]|select(.sourceType=="SATELLITE_NDVI" and .eligibility=="ELIGIBLE" and (.observedAt|startswith("2024-06-18")))]|length')
expect_eq "$LATE_IN_FEATURES" "0" "late scene not used by any feature"

OLD_REV=$(echo "$DETAIL" | "$JQ" -c '[.inputs[]|select(.eligibility=="ELIGIBLE" or .eligibility=="ELIGIBLE_OLDER_REVISION_USED")|select(.ingestedAt > "2024-06-22T00:00:00Z")]|length')
# ingestedAt strings here are ISO with Z; Postgres times render in UTC. This is a belt-and-braces check.
expect_eq "$OLD_REV" "0" "no eligible input ingested after the cutoff"

SNAP_BEFORE=$(json "$BASE/api/cases/$CASE/snapshots" | "$JQ" -r '.[0].inputs_hash')
echo "  snapshot inputs_hash $SNAP_BEFORE"

# ---------------------------------------------------------------------------
say "scenario 2: overlapping boundary versions must not double count pixels"
PARCEL_ID=$(json "$BASE/api/warehouse/parcels" | "$JQ" -r '[.[]|select(.parcel_code=="P-1001")][0].id')
V2=$(json "$BASE/api/warehouse/parcels/$PARCEL_ID/boundaries" | "$JQ" -r '[.[]|select(.versionNo==2)][0].id')
VAR=$(json -XPOST "$BASE/api/impact/boundary-variant-runs" -H 'Content-Type: application/json' \
  -d "{\"caseNo\":\"$CASE\",\"boundaryVersionId\":$V2,\"reason\":\"verification\"}" | "$JQ" -r '.runId')
json -XPOST "$BASE/api/runs/$VAR/execute" >/dev/null
VDETAIL=$(json "$BASE/api/runs/$VAR")
VEXCL=$(echo "$VDETAIL" | "$JQ" -c '[.inputs[]|select(.eligibility=="EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL")]|length')
[ "$VEXCL" -ge 1 ] && ok "variant excludes $VEXCL shared cell(s)" || bad "variant did not exclude shared cells"
VFLAG=$(echo "$VDETAIL" | "$JQ" -r '.run.boundaryVariant')
expect_eq "$VFLAG" "true" "variant run flagged"

# The original case's snapshot must be unchanged by running the variant.
SNAP_AFTER=$(json "$BASE/api/cases/$CASE/snapshots" | "$JQ" -r '.[0].inputs_hash')
expect_eq "$SNAP_AFTER" "$SNAP_BEFORE" "snapshot unchanged by variant run"

# ---------------------------------------------------------------------------
say "scenario 3: post-settlement weather revision creates a review candidate only"
# Settle the case first so the revision arrives strictly after payment. Already-settled cases
# from a previous run are accepted as-is (the script is safe to run twice).
CUR=$(json "$BASE/api/cases/$CASE" | "$JQ" -r '.summary.status')
if [ "$CUR" != "PAID" ]; then
  json -XPOST "$BASE/api/cases/$CASE/settle" -H 'Content-Type: application/json' \
    -d '{"settledBy":"claims-officer","note":"paid before the corrected rainfall arrived"}' >/dev/null
fi
PAID=$(json "$BASE/api/cases/$CASE" | "$JQ" -r '.summary.status')
expect_eq "$PAID" "PAID" "case settled before the revision"

EV=$(json "$BASE/api/warehouse/revision-events?sourceType=WEATHER_OBSERVATION" | \
  "$JQ" -r '[.[]|select(.revision_no==2)][0].id')
ANALYSIS=$(json -XPOST "$BASE/api/impact/analyses/by-revision/$EV")
EFFECT=$(echo "$ANALYSIS" | "$JQ" -r "[.cases[]|select(.caseNo==\"$CASE\")][0].effect")
expect_eq "$EFFECT" "RECOMPUTE_CANDIDATE" "impact effect for the settled case"
CAND=$(echo "$ANALYSIS" | "$JQ" -r "[.cases[]|select(.caseNo==\"$CASE\")][0].candidateId")
if [ "$CAND" = "null" ]; then
  # A previous run may already have produced the candidate; reuse it.
  CAND=$(json "$BASE/api/review/candidates" | "$JQ" -r "[.[]|select(.caseNo==\"$CASE\" and .status==\"PENDING_REVIEW\")][0].id // empty")
fi
[ -n "$CAND" ] && ok "review candidate $CAND available" || bad "no review candidate created"

CSTATUS=$(json "$BASE/api/review/candidates/$CAND" | "$JQ" -r '.status')
expect_eq "$CSTATUS" "PENDING_REVIEW" "candidate status"
CDELTA=$(json "$BASE/api/review/candidates/$CAND" | "$JQ" -c '.delta')
echo "  candidate delta $CDELTA"

STILL_PAID=$(json "$BASE/api/cases/$CASE" | "$JQ" -r '.summary.status')
expect_eq "$STILL_PAID" "PAID" "settled case status not downgraded"

SNAP_POST=$(json "$BASE/api/cases/$CASE/snapshots" | "$JQ" -r '.[0].inputs_hash')
expect_eq "$SNAP_POST" "$SNAP_BEFORE" "frozen snapshot untouched by impact analysis"
SNAP_COUNT=$(json "$BASE/api/cases/$CASE/snapshots" | "$JQ" 'length')
expect_eq "$SNAP_COUNT" "1" "still exactly one frozen snapshot"
# ---------------------------------------------------------------------------
say "reproduction: replay at the original cutoff must reproduce the snapshot"
REPRO=$(json -XPOST "$BASE/api/cases/$CASE/reproduce")
MATCH=$(echo "$REPRO" | "$JQ" -r '.valuesMatch')
RSTATUS=$(echo "$REPRO" | "$JQ" -r '.replayStatus')
expect_eq "$RSTATUS" "SUCCEEDED" "replay run status"
expect_eq "$MATCH" "true" "replay values match the frozen snapshot"

# ---------------------------------------------------------------------------
printf '\n'
if [ "$fail" -eq 0 ]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "SOME CHECKS FAILED"
  exit 1
fi
