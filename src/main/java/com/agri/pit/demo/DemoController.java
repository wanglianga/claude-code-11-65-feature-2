package com.agri.pit.demo;

import com.agri.pit.common.Db;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final Db db;

    public DemoController(Db db) {
        this.db = db;
    }

    /** Self-describing index of the sample scenarios and their expected endpoints. */
    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cases", List.of(
                Map.of("caseNo", DemoSeeder.CASE_PRIMARY,
                        "parcel", "P-1001",
                        "lockedBoundary", "v1",
                        "note", "locks boundary v1; a variant run on v2 must not double count shared pixels"),
                Map.of("caseNo", DemoSeeder.CASE_SECONDARY,
                        "parcel", "P-1002",
                        "lockedBoundary", "v1",
                        "note", "control case without overlapping boundaries"),
                Map.of("caseNo", DemoSeeder.CASE_NEIGHBOUR,
                        "parcel", "P-1003",
                        "lockedBoundary", "v1",
                        "note", "neighbouring farmer whose registry map overlaps P-1001 beyond tolerance")));
        body.put("scenario1LateScene", Map.of(
                "scene", DemoSeeder.SCENE_LATE,
                "acquisition", "2024-06-18T09:15:00Z",
                "ingested", "2024-06-25T03:00:00Z",
                "caseCutoff", DemoSeeder.CASE_CUTOFF.toString(),
                "expected", "original scoring run verdict PASS_WITH_EXCLUSIONS (or BLOCKED if required data "
                        + "vanishes); the late scene appears as EXCLUDED_LATE_AVAILABILITY in the input ledger",
                "howToCheck", List.of(
                        "GET /api/cases/" + DemoSeeder.CASE_PRIMARY,
                        "GET /api/cases/" + DemoSeeder.CASE_PRIMARY + "/trace",
                        "GET /api/runs/{runId} -> inputs[] and leakage.violations[]")));
        body.put("scenario2OverlappingBoundaries", Map.of(
                "parcel", "P-1001",
                "versions", List.of("v1 (locked by case)", "v2 (re-survey, overlaps v1)"),
                "expected", "variant run on v2 records the shared cells as EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL "
                        + "and never adds them into v2's mean NDVI",
                "howToCheck", List.of(
                        "POST /api/impact/boundary-variant-runs {caseNo, boundaryVersionId}",
                        "GET /api/runs/{variantRunId} -> inputs[] with exclusion verdicts")));
        body.put("scenario3WeatherRevision", Map.of(
                "station", "WS-001",
                "observation", DemoSeeder.WEATHER_REVISION_KEY,
                "revisionIngested", "2024-07-08T02:00:00Z",
                "expected", "impact analysis reports RECOMPUTE_CANDIDATE, a controlled backfill runs, and a "
                        + "PENDING_REVIEW candidate is created; the frozen snapshot and payout stay unchanged",
                "howToCheck", List.of(
                        "GET /api/warehouse/revision-events?sourceType=WEATHER_OBSERVATION",
                        "POST /api/impact/analyses/by-revision/{revisionEventId}",
                        "GET /api/review/candidates?status=PENDING_REVIEW")));
        body.put("counts", db.jdbc().queryForMap("""
                select (select count(*) from claim_case) as cases,
                       (select count(*) from scoring_snapshot) as snapshots,
                       (select count(*) from review_candidate) as candidates,
                       (select count(*) from feature_run) as runs,
                       (select count(*) from parcel_tenure_version) as tenureVersions,
                       (select count(*) from case_geometry_freeze) as geometryFreezes,
                       (select count(*) from boundary_dispute) as disputes
                """));
        body.put("scenario4TenureAndFreeze", Map.of(
                "parcel", "P-1001",
                "expected", "the intake as-of tenure query resolves to tenure v1/boundary v1; the transfer "
                        + "tenure v2 is approved after the cutoff and never changes the accepted case; each "
                        + "case gets an immutable case_geometry_freeze row",
                "howToCheck", List.of(
                        "GET /api/tenure/parcels/{parcelId}/versions",
                        "GET /api/tenure/parcels/{parcelId}/as-of?at=2024-06-22T00:00:00Z",
                        "GET /api/boundary/cases/" + DemoSeeder.CASE_PRIMARY + "/geometry-freeze")));
        body.put("scenario5OverlapDispute", Map.of(
                "parcels", List.of("P-1001", "P-1003"),
                "expected", "the overlap graph flags the cross-parcel pair as exceedsTolerance; shared "
                        + "pixels are PENDING_ARBITRATION and attributed to neither claim; the dispute desk "
                        + "ruling can be withdrawn and a new DISPUTE_RESAMPLING run keeps prior rulings/areas",
                "howToCheck", List.of(
                        "POST /api/boundary/parcels/{parcelId}/overlap-analyses",
                        "GET /api/boundary/parcels/{parcelId}/overlap-graph",
                        "GET /api/disputes?status=OPEN",
                        "POST /api/disputes/{id}/rulings",
                        "POST /api/disputes/{id}/withdrawal",
                        "POST /api/disputes/{id}/review-requests")));
        return body;
    }
}
