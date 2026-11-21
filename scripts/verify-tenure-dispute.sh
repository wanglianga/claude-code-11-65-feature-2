#!/usr/bin/env bash
# End-to-end verification of the tenure / geometry-governance feature set:
#   scenario 4 - tenure version library, as-of query and immutable case geometry freeze
#   scenario 5 - overlap graph, over-tolerance pixel arbitration, dispute ruling, withdrawal and
#                review requests that always create a fresh sampling run
# Requires a running seeded instance (default http://localhost:8080). Safe to run repeatedly.
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

parcel_id() { json "$BASE/api/warehouse/parcels" | "$JQ" -r --arg code "$1" '.[]|select(.parcel_code==$code)|.id'; }
boundary_id() { # parcelId versionNo
  json "$BASE/api/warehouse/parcels/$1/boundaries" | "$JQ" -r --argjson v "$2" '[.[]|select(.versionNo==$v)][0].id';
}

P1=$(parcel_id P-1001)
P3=$(parcel_id P-1003)
P1V1=$(boundary_id "$P1" 1)
P3V1=$(boundary_id "$P3" 1)

# ---------------------------------------------------------------------------
say "scenario 4: tenure as-of query resolves the intake-approved geometry"
AS_OF_CUTOFF=$(json "$BASE/api/tenure/parcels/$P1/as-of?at=2024-06-22T00:00:00Z")
expect_eq "$(echo "$AS_OF_CUTOFF" | "$JQ" -r '.code')" "APPROVED_TENURE_RESOLVED" "as-of cutoff resolves"
expect_eq "$(echo "$AS_OF_CUTOFF" | "$JQ" -r '.tenure.boundaryVersionId')" "$P1V1" "as-of cutoff locks boundary v1"

AS_OF_LATER=$(json "$BASE/api/tenure/parcels/$P1/as-of?at=2024-06-26T00:00:00Z")
expect_eq "$(echo "$AS_OF_LATER" | "$JQ" -r '.tenure.versionNo')" "2" "later as-of resolves transferred tenure v2"
LATER_BV=$(echo "$AS_OF_LATER" | "$JQ" -r '.tenure.boundaryVersionId')
if [ "$LATER_BV" != "$P1V1" ]; then ok "newer tenure points at a different boundary (v$LATER_BV vs v$P1V1)"; else bad "tenure v2 should reference the re-surveyed boundary"; fi

say "scenario 4: case geometry freeze is immutable and independent per case"
FREEZE=$(json "$BASE/api/boundary/cases/DROUGHT-2024-0001/geometry-freeze")
expect_eq "$(echo "$FREEZE" | "$JQ" -r '.boundaryVersionId')" "$P1V1" "accepted case freezes intake boundary v1"
[ "$(echo "$FREEZE" | "$JQ" -r '.geometrySnapshot.type')" = "Polygon" ] \
  && ok "freeze stores a full geometry snapshot" || bad "freeze geometry snapshot missing"
FROZEN_AREA=$(echo "$FREEZE" | "$JQ" -r '.areaHa')
echo "  frozen area $FROZEN_AREA ha (newest registry map must not change it)"

# ---------------------------------------------------------------------------
say "scenario 5: overlap graph flags the cross-parcel pair as over tolerance"
ANALYSIS=$(json -XPOST "$BASE/api/boundary/parcels/$P1/overlap-analyses" -H 'Content-Type: application/json' \
  -d '{"tolerancePct":5.0,"scope":"BOTH","createdBy":"verify-script"}')
EDGE=$(echo "$ANALYSIS" | "$JQ" -c --arg a "$P1V1" --arg b "$P3V1" \
  '[.graph.edges[]|select((.a.boundaryVersionId==($a|tonumber) and .b.boundaryVersionId==($b|tonumber)) or (.a.boundaryVersionId==($b|tonumber) and .b.boundaryVersionId==($a|tonumber)))][0]')
[ -n "$EDGE" ] && [ "$EDGE" != "null" ] && ok "overlap edge P-1001 v1 <-> P-1003 v1 exists" || bad "cross-parcel overlap edge missing"
expect_eq "$(echo "$EDGE" | "$JQ" -r '.sameParcel')" "false" "edge is cross-parcel"
expect_eq "$(echo "$EDGE" | "$JQ" -r '.exceedsTolerance')" "true" "edge exceeds the 5% tolerance"

DISPUTE_ID=$(json "$BASE/api/disputes?status=OPEN" | "$JQ" -r --arg a "$P1V1" --arg b "$P3V1" \
  '[.[]|select((.subjectBoundaryVersionIdA==($a|tonumber) and .subjectBoundaryVersionIdB==($b|tonumber))
                       or (.subjectBoundaryVersionIdA==($b|tonumber) and .subjectBoundaryVersionIdB==($a|tonumber)))][0].id // empty')
if [ -z "$DISPUTE_ID" ]; then
  # A previous run may have ruled/withdrawn it; search every status.
  DISPUTE_ID=$(json "$BASE/api/disputes" | "$JQ" -r --arg a "$P1V1" --arg b "$P3V1" \
    '[.[]|select((.subjectBoundaryVersionIdA==($a|tonumber) and .subjectBoundaryVersionIdB==($b|tonumber))
                         or (.subjectBoundaryVersionIdA==($b|tonumber) and .subjectBoundaryVersionIdB==($a|tonumber)))][0].id')
fi
[ -n "$DISPUTE_ID" ] && ok "dispute $DISPUTE_ID opened for the over-tolerance pair" || bad "no dispute opened for over-tolerance overlap"

# Idempotency: a previous run may have left the dispute RULED; withdraw before re-ruling.
DSTATUS=$(json "$BASE/api/disputes/$DISPUTE_ID" | "$JQ" -r '.status')
if [ "$DSTATUS" = "RULED" ]; then
  json -XPOST "$BASE/api/disputes/$DISPUTE_ID/withdrawal" -H 'Content-Type: application/json' \
    -d '{"withdrawnBy":"verify-arbiter","note":"reset for re-verification"}' >/dev/null
fi

PENDING=$(json "$BASE/api/disputes/pixel-allocations?disputeId=$DISPUTE_ID&status=PENDING_ARBITRATION" | "$JQ" 'length')
[ "$PENDING" -ge 1 ] && ok "$PENDING pixel allocation(s) queued for arbitration" || bad "no PENDING_ARBITRATION pixels"
DOUBLE=$(json "$BASE/api/disputes/pixel-allocations?disputeId=$DISPUTE_ID" | "$JQ" '[.[]|select(.allocationStatus=="ATTRIBUTED")]|length')
echo "  attributed allocations inside the disputed overlap: $DOUBLE (ruling decides ownership later)"

# ---------------------------------------------------------------------------
say "scenario 5: ruling -> review creates a new sampling run on the frozen geometry"
json -XPOST "$BASE/api/disputes/$DISPUTE_ID/rulings" -H 'Content-Type: application/json' \
  -d '{"ruling":"GRANT_TO_A","decidedBy":"verify-arbiter","note":"verification ruling"}' >/dev/null
RR1=$(json -XPOST "$BASE/api/disputes/$DISPUTE_ID/review-requests" -H 'Content-Type: application/json' \
  -d '{"requestedBy":"verify-reviewer","reason":"first re-sampling"}')
RUN1=$(echo "$RR1" | "$JQ" -r .samplingRunId)
expect_eq "$(echo "$RR1" | "$JQ" -r '.priorAreaHa')" "$FROZEN_AREA" "review retains the original frozen area"
json -XPOST "$BASE/api/runs/$RUN1/execute" >/dev/null
RUN1_VIEW=$(json "$BASE/api/runs/$RUN1")
expect_eq "$(echo "$RUN1_VIEW" | "$JQ" -r '.run.triggerType')" "DISPUTE_RESAMPLING" "first sampling run trigger"
expect_eq "$(echo "$RUN1_VIEW" | "$JQ" -r '.run.boundaryVersionId')" "$P1V1" "sampling run reuses the intake boundary v1"
GRANTED=$(json "$BASE/api/disputes/pixel-allocations?runId=$RUN1" | "$JQ" '[.[]|select(.ruleCode=="DISPUTE_RULING_GRANT" and .allocationStatus=="ATTRIBUTED")]|length')
[ "$GRANTED" -ge 1 ] && ok "ruling grants $GRANTED disputed cell(s) to boundary v1" || bad "ruling grant not applied to pixels"

# ---------------------------------------------------------------------------
say "scenario 5: withdrawal -> another review creates another run, prior ruling/area retained"
json -XPOST "$BASE/api/disputes/$DISPUTE_ID/withdrawal" -H 'Content-Type: application/json' \
  -d '{"withdrawnBy":"verify-arbiter","note":"new field evidence"}' >/dev/null
RULING_COUNT=$(json "$BASE/api/disputes/$DISPUTE_ID" | "$JQ" '.rulings|length')
[ "$RULING_COUNT" -ge 2 ] && ok "original ruling and withdrawal both retained ($RULING_COUNT ruling rows)" || bad "ruling history not retained"
RR2=$(json -XPOST "$BASE/api/disputes/$DISPUTE_ID/review-requests" -H 'Content-Type: application/json' \
  -d '{"requestedBy":"verify-reviewer","reason":"second re-sampling after withdrawal"}')
RUN2=$(echo "$RR2" | "$JQ" -r .samplingRunId)
if [ "$RUN1" != "$RUN2" ]; then ok "second review created a distinct new sampling run ($RUN1 -> $RUN2)"; else bad "second review reused run $RUN1"; fi
expect_eq "$(echo "$RR2" | "$JQ" -r '.priorAreaHa')" "$FROZEN_AREA" "second review still references the original area"
json -XPOST "$BASE/api/runs/$RUN2/execute" >/dev/null
expect_eq "$(json "$BASE/api/runs/$RUN2" | "$JQ" -r '.run.triggerType')" "DISPUTE_RESAMPLING" "second sampling run trigger"
REQUEUED=$(json "$BASE/api/disputes/pixel-allocations?runId=$RUN2&status=PENDING_ARBITRATION" | "$JQ" 'length')
[ "$REQUEUED" -ge 1 ] && ok "withdrawn ruling puts $REQUEUED cell(s) back into arbitration" || bad "cells not re-queued after withdrawal"

# ---------------------------------------------------------------------------
printf '\n'
if [ "$fail" -eq 0 ]; then
  echo "ALL TENURE/DISPUTE CHECKS PASSED"
  exit 0
else
  echo "SOME TENURE/DISPUTE CHECKS FAILED"
  exit 1
fi
