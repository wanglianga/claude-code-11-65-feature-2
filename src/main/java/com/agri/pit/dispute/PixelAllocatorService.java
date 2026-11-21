package com.agri.pit.dispute;

import com.agri.pit.boundary.GeometryFreezeService;
import com.agri.pit.boundary.OverlapAnalysisService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.GeoUtil;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pixel allocator.
 * <p>
 * For every raster cell inside a run's locked boundary, the allocator decides one of three
 * outcomes and persists the decision in {@code pixel_allocation}:
 * <ul>
 *   <li><b>ATTRIBUTED</b> — the cell may enter this run's NDVI mean. Either it is covered by a
 *       single boundary version, or every competing overlap is within the policy tolerance and the
 *       deterministic priority (locked case first, then parcel/version id) grants it to this run.</li>
 *   <li><b>EXCLUDED</b> — an under-tolerance overlap was granted to the other parcel/version, or a
 *       dispute ruling excludes the cell from this side.</li>
 *   <li><b>PENDING_ARBITRATION</b> — the cell sits in an overlap whose area exceeds the policy
 *       tolerance. It is never attributed to either claim; a boundary dispute is opened (or reused)
 *       and the cell stays excluded until a human ruling.</li>
 * </ul>
 * The decision is a pure function of stored registry/case/ruling data, so replay and re-sampling
 * runs reproduce it deterministically.
 */
@Service
public class PixelAllocatorService {

    public static final String RULE_SINGLE_OWNER = "SINGLE_BOUNDARY_OWNER";
    public static final String RULE_PRIORITY = "DETERMINISTIC_PRIORITY";
    public static final String RULE_OVER_TOLERANCE = "OVERLAP_EXCEEDS_TOLERANCE";
    public static final String RULE_RULING_GRANT = "DISPUTE_RULING_GRANT";
    public static final String RULE_RULING_EXCLUDE = "DISPUTE_RULING_EXCLUDE_BOTH";
    public static final String RULE_RULING_RESURVEY = "DISPUTE_RULING_REQUIRES_RESURVEY";

    private final Db db;
    private final GeoUtil geo;
    private final OverlapAnalysisService overlapService;
    private final GeometryFreezeService freezer;

    public PixelAllocatorService(Db db, GeoUtil geo, OverlapAnalysisService overlapService,
                                 GeometryFreezeService freezer) {
        this.db = db;
        this.geo = geo;
        this.overlapService = overlapService;
        this.freezer = freezer;
    }

    public record BoundaryCandidate(long boundaryVersionId, long parcelId, int versionNo,
                                    Long lockedCaseId, Geometry geometry) {
    }

    public record Decision(boolean usableByCurrentRun, String allocationStatus, String ruleCode,
                           Long disputeId, String reason) {
    }

    /** Per-run context: candidate boundaries are loaded once and overlap metrics are cached. */
    public class AllocationContext {
        public final long runId;
        public final Long caseId;
        public final long parcelId;
        public final long boundaryVersionId;
        public final Instant ceiling;
        public final double tolerancePct;
        private final Map<String, OverlapAnalysisService.OverlapMetric> metricCache = new HashMap<>();

        AllocationContext(long runId, Long caseId, long parcelId, long boundaryVersionId,
                          Instant ceiling, double tolerancePct) {
            this.runId = runId;
            this.caseId = caseId;
            this.parcelId = parcelId;
            this.boundaryVersionId = boundaryVersionId;
            this.ceiling = ceiling;
            this.tolerancePct = tolerancePct;
        }

        OverlapAnalysisService.OverlapMetric metric(long a, long b) {
            String key = Math.min(a, b) + ":" + Math.max(a, b);
            return metricCache.computeIfAbsent(key, ignored -> {
                var rowA = overlapService.boundaryVersion(a);
                var rowB = overlapService.boundaryVersion(b);
                return overlapService.metric(rowA, rowB, tolerancePct);
            });
        }
    }

    public AllocationContext newContext(long runId, Long caseId, long parcelId,
                                        long boundaryVersionId, Instant ceiling) {
        double tolerance = OverlapAnalysisService.DEFAULT_TOLERANCE_PCT;
        if (caseId != null) {
            GeometryFreezeService.FreezeRow freeze = freezer.findByCaseId(caseId);
            if (freeze != null) {
                tolerance = freeze.policyTolerancePct();
            }
        }
        return new AllocationContext(runId, caseId, parcelId, boundaryVersionId, ceiling, tolerance);
    }

    /** All boundary versions visible at the ceiling, plus any version locked by an accepted case. */
    public List<BoundaryCandidate> candidateBoundaries(Instant ceiling) {
        return db.jdbc().query("""
                        select b.id, b.parcel_id, b.version_no, b.geometry_geojson,
                               (select min(c.id) from claim_case c where c.boundary_version_id = b.id) as case_id
                          from parcel_boundary_version b
                         where b.ingested_at <= ?
                            or exists (select 1 from claim_case c where c.boundary_version_id = b.id)
                         order by b.id
                        """, (rs, i) -> new BoundaryCandidate(rs.getLong("id"), rs.getLong("parcel_id"),
                        rs.getInt("version_no"), (Long) rs.getObject("case_id"),
                        geo.parse(db.readTree(rs.getString("geometry_geojson")))),
                Db.timestamp(ceiling));
    }

    /**
     * Allocate one cell. {@code containing} holds every candidate boundary whose polygon covers
     * the cell centroid; the run's own boundary is always among them.
     */
    @Transactional
    public Decision decide(AllocationContext ctx, List<BoundaryCandidate> containing,
                           long sceneId, long cellId, double ndvi) {
        BoundaryCandidate current = containing.stream()
                .filter(c -> c.boundaryVersionId() == ctx.boundaryVersionId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("run boundary is not a containing candidate"));
        Decision decision;
        if (containing.size() == 1) {
            decision = new Decision(true, "ATTRIBUTED", RULE_SINGLE_OWNER, null,
                    "cell is covered only by boundary v" + current.versionNo());
        } else {
            decision = decideContested(ctx, containing, current);
        }
        persist(ctx, sceneId, cellId, current, ndvi, decision);
        return decision;
    }

    private Decision decideContested(AllocationContext ctx, List<BoundaryCandidate> containing,
                                     BoundaryCandidate current) {
        // Arbitration protects two farmers / two claims, so only CROSS-parcel competitors qualify.
        // Old-vs-new versions of the same parcel can never back a double payout: they fall through
        // to the deterministic priority (the case-locked version wins, variant runs exclude).
        BoundaryCandidate crossCompetitor = null;
        double crossOverlapPct = 0d;
        OverlapAnalysisService.OverlapMetric crossMetric = null;
        for (BoundaryCandidate candidate : containing) {
            if (candidate.boundaryVersionId() == current.boundaryVersionId()
                    || candidate.parcelId() == current.parcelId()) {
                continue;
            }
            OverlapAnalysisService.OverlapMetric metric =
                    ctx.metric(current.boundaryVersionId(), candidate.boundaryVersionId());
            double pct = Math.max(metric.pctOfA(), metric.pctOfB());
            if (pct >= crossOverlapPct) {
                crossOverlapPct = pct;
                crossCompetitor = candidate;
                crossMetric = metric;
            }
        }
        if (crossMetric != null && crossMetric.exceeds()) {
            // Over-tolerance cross-parcel overlap: no side may claim the pixel until the desk rules.
            long disputeId = openOrReuseDispute(ctx, current, crossCompetitor, crossMetric);
            DisputeState dispute = loadDispute(disputeId);
            return applyDispute(dispute, ctx, current, crossCompetitor);
        }
        // Within tolerance (or a same-parcel version overlap): keep the fields isolated by the
        // existing deterministic priority.
        BoundaryCandidate owner = containing.get(0);
        for (BoundaryCandidate candidate : containing) {
            if (better(candidate, owner)) {
                owner = candidate;
            }
        }
        if (owner.boundaryVersionId() == current.boundaryVersionId()) {
            return new Decision(true, "ATTRIBUTED", RULE_PRIORITY, null,
                    "within-tolerance overlap; deterministic priority grants the cell to boundary v"
                            + owner.versionNo());
        }
        return new Decision(false, "EXCLUDED", RULE_PRIORITY, null,
                "within-tolerance overlap; cell granted to parcel " + owner.parcelId()
                        + " boundary v" + owner.versionNo());
    }

    private Decision applyDispute(DisputeState dispute, AllocationContext ctx,
                                  BoundaryCandidate current, BoundaryCandidate competitor) {
        return switch (dispute.status()) {
            case "OPEN" -> new Decision(false, "PENDING_ARBITRATION", RULE_OVER_TOLERANCE, dispute.id(),
                    "overlap with boundary v" + competitor.versionNo() + " exceeds the "
                            + ctx.tolerancePct + "% tolerance; awaiting a desk ruling");
            case "WITHDRAWN" -> new Decision(false, "PENDING_ARBITRATION", RULE_OVER_TOLERANCE, dispute.id(),
                    "previous ruling withdrawn; the overlap is back in the arbitration queue");
            case "RULED" -> applyRuling(dispute, current, competitor);
            default -> new Decision(false, "PENDING_ARBITRATION", RULE_OVER_TOLERANCE, dispute.id(),
                    "dispute " + dispute.status() + "; cell kept out of scoring");
        };
    }

    private Decision applyRuling(DisputeState dispute, BoundaryCandidate current,
                                 BoundaryCandidate competitor) {
        long granted;
        switch (dispute.ruling() == null ? "" : dispute.ruling()) {
            case "GRANT_TO_A" -> granted = dispute.boundaryA();
            case "GRANT_TO_B" -> granted = dispute.boundaryB();
            case "EXCLUDE_BOTH" -> {
                return new Decision(false, "EXCLUDED", RULE_RULING_EXCLUDE, dispute.id(),
                        "desk ruling EXCLUDE_BOTH: the cell is attributed to neither claim");
            }
            case "RE_SURVEY_REQUIRED" -> {
                return new Decision(false, "PENDING_ARBITRATION", RULE_RULING_RESURVEY, dispute.id(),
                        "desk ruling requires a new survey before the cell can be attributed");
            }
            default -> {
                return new Decision(false, "PENDING_ARBITRATION", RULE_OVER_TOLERANCE, dispute.id(),
                        "unknown ruling state; cell kept out of scoring");
            }
        }
        if (granted == current.boundaryVersionId()) {
            return new Decision(true, "ATTRIBUTED", RULE_RULING_GRANT, dispute.id(),
                    "desk ruling grants the overlap to boundary v" + current.versionNo());
        }
        return new Decision(false, "EXCLUDED", RULE_RULING_GRANT, dispute.id(),
                "desk ruling grants the overlap to boundary v" + competitor.versionNo());
    }

    // ---------------------------------------------------------------- disputes

    public record DisputeState(long id, String status, String ruling, long boundaryA, long boundaryB) {
    }

    public DisputeState loadDispute(long disputeId) {
        List<DisputeState> rows = db.jdbc().query("""
                        select id, status, ruling, subject_boundary_version_id_a,
                               subject_boundary_version_id_b
                          from boundary_dispute where id = ?
                        """,
                (rs, i) -> new DisputeState(rs.getLong("id"), rs.getString("status"),
                        rs.getString("ruling"), rs.getLong("subject_boundary_version_id_a"),
                        rs.getLong("subject_boundary_version_id_b")),
                disputeId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("DISPUTE_NOT_FOUND", "boundary dispute not found: " + disputeId);
        }
        return rows.get(0);
    }

    private long openOrReuseDispute(AllocationContext ctx, BoundaryCandidate current,
                                    BoundaryCandidate competitor,
                                    OverlapAnalysisService.OverlapMetric metric) {
        List<Long> existing = db.jdbc().query("""
                        select id from boundary_dispute
                         where status in ('OPEN','RULED','WITHDRAWN')
                           and case_id is not distinct from ?
                           and ((subject_boundary_version_id_a = ? and subject_boundary_version_id_b = ?)
                             or (subject_boundary_version_id_a = ? and subject_boundary_version_id_b = ?))
                         order by id desc limit 1
                        """,
                (rs, i) -> rs.getLong(1), ctx.caseId,
                current.boundaryVersionId(), competitor.boundaryVersionId(),
                competitor.boundaryVersionId(), current.boundaryVersionId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        long low = Math.min(current.boundaryVersionId(), competitor.boundaryVersionId());
        long high = Math.max(current.boundaryVersionId(), competitor.boundaryVersionId());
        String disputeNo = "DSP-C" + (ctx.caseId == null ? "X" : ctx.caseId) + "-B" + low + "-" + high;
        return db.insert("""
                        insert into boundary_dispute
                            (dispute_no, case_id, parcel_id, overlap_analysis_id, overlap_pair_id,
                             subject_boundary_version_id_a, subject_boundary_version_id_b, overlap_area_ha,
                             tolerance_pct, status, reason, opened_by)
                        values (?, ?, ?, null, null, ?, ?, ?, ?, 'OPEN', ?, 'pixel-allocator')
                        on conflict (dispute_no) do update set reason = boundary_dispute.reason
                        returning id
                        """,
                disputeNo, ctx.caseId, ctx.parcelId,
                current.boundaryVersionId(), competitor.boundaryVersionId(),
                metric.intersectionHa(), ctx.tolerancePct,
                "auto-opened: overlap of " + Math.round(metric.intersectionHa() * 10000d) / 10000d
                        + " ha exceeds the " + ctx.tolerancePct + "% policy tolerance");
    }

    // ------------------------------------------------------------- persistence

    private void persist(AllocationContext ctx, long sceneId, long cellId, BoundaryCandidate current,
                         double ndvi, Decision decision) {
        db.insert("""
                        insert into pixel_allocation
                            (scene_id, cell_id, run_id, case_id, parcel_id, boundary_version_id,
                             allocation_status, rule_code, dispute_id, ndvi, reason)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                sceneId, cellId, ctx.runId, ctx.caseId, current.parcelId(),
                current.boundaryVersionId(), decision.allocationStatus(), decision.ruleCode(),
                decision.disputeId(), ndvi, decision.reason());
    }

    public List<DisputeDtos.AllocationView> listAllocations(Long runId, String status, Long disputeId) {
        StringBuilder sql = new StringBuilder("""
                select id, scene_id, cell_id, run_id, case_id, parcel_id, boundary_version_id,
                       allocation_status, rule_code, overlap_analysis_id, dispute_id, ndvi, reason,
                       allocated_at
                  from pixel_allocation where 1 = 1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (runId != null) {
            sql.append(" and run_id = ?");
            args.add(runId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and allocation_status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (disputeId != null) {
            sql.append(" and dispute_id = ?");
            args.add(disputeId);
        }
        sql.append(" order by id desc limit 500");
        return db.jdbc().query(sql.toString(), (rs, i) -> new DisputeDtos.AllocationView(
                rs.getLong("id"), rs.getLong("scene_id"), rs.getLong("cell_id"),
                (Long) rs.getObject("run_id"), (Long) rs.getObject("case_id"),
                rs.getLong("parcel_id"), rs.getLong("boundary_version_id"),
                rs.getString("allocation_status"), rs.getString("rule_code"),
                (Long) rs.getObject("overlap_analysis_id"), (Long) rs.getObject("dispute_id"),
                rs.getBigDecimal("ndvi").doubleValue(), rs.getString("reason"),
                com.agri.pit.common.Times.format(Db.instant(rs, "allocated_at"))),
                args.toArray());
    }

    /** Deterministic priority, unchanged from the original single-owner allocation rule. */
    static boolean better(BoundaryCandidate candidate, BoundaryCandidate current) {
        boolean candLocked = candidate.lockedCaseId() != null;
        boolean currLocked = current.lockedCaseId() != null;
        if (candLocked != currLocked) {
            return candLocked;
        }
        if (candLocked) {
            return candidate.lockedCaseId() < current.lockedCaseId();
        }
        if (candidate.parcelId() != current.parcelId()) {
            return candidate.parcelId() < current.parcelId();
        }
        return candidate.boundaryVersionId() < current.boundaryVersionId();
    }

    // Used by the dispute desk to mark queued cells after a ruling / withdrawal.
    @Transactional
    public void requeueCellsForDispute(long disputeId) {
        db.jdbc().update("""
                        update pixel_allocation
                           set allocation_status = 'PENDING_ARBITRATION',
                               rule_code = ?, reason = 'ruling withdrawn; cell re-queued for arbitration'
                         where dispute_id = ? and allocation_status in ('ATTRIBUTED','EXCLUDED')
                        """, RULE_OVER_TOLERANCE, disputeId);
    }
}
