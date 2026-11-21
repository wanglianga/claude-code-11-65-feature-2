package com.agri.pit.dispute;

import com.agri.pit.boundary.GeometryFreezeService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.runs.RunService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Boundary dispute desk.
 * <p>
 * Manual rulings and their withdrawal are append-only ({@code boundary_dispute_ruling}); the
 * original ruling and the original area result (frozen geometry area + scoring snapshot) are never
 * deleted. After a withdrawal, requesting another review creates a brand-new DISPUTE_RESAMPLING
 * feature run that still reads the case's intake-frozen geometry, while linking back to the prior
 * ruling and prior area so the whole chain is auditable.
 */
@Service
public class DisputeDeskService {

    public static final Set<String> RULINGS =
            Set.of("GRANT_TO_A", "GRANT_TO_B", "EXCLUDE_BOTH", "RE_SURVEY_REQUIRED");

    private final Db db;
    private final RunService runs;
    private final GeometryFreezeService freezer;
    private final PixelAllocatorService allocator;

    public DisputeDeskService(Db db, RunService runs, GeometryFreezeService freezer,
                              PixelAllocatorService allocator) {
        this.db = db;
        this.runs = runs;
        this.freezer = freezer;
        this.allocator = allocator;
    }

    public record DisputeRow(long id, String disputeNo, Long caseId, long parcelId,
                             Long overlapAnalysisId, Long overlapPairId, long boundaryA, long boundaryB,
                             double overlapAreaHa, double tolerancePct, String status, String reason,
                             String openedBy, Instant openedAt, String ruling, String ruledBy,
                             Instant ruledAt, String rulingNote, String withdrawnBy, Instant withdrawnAt,
                             String withdrawalNote, Instant closedAt) {
    }

    // --------------------------------------------------------------- queries

    public List<DisputeDtos.DisputeView> list(String status) {
        String sql = "select * from boundary_dispute"
                + (status == null || status.isBlank() ? "" : " where status = ?")
                + " order by id desc";
        List<DisputeRow> rows = status == null || status.isBlank()
                ? db.jdbc().query(sql, (rs, i) -> map(rs))
                : db.jdbc().query(sql, (rs, i) -> map(rs), status.trim().toUpperCase());
        return rows.stream().map(this::toView).toList();
    }

    public DisputeDtos.DisputeView getView(long disputeId) {
        return toView(require(disputeId));
    }

    public DisputeRow require(long disputeId) {
        List<DisputeRow> rows = db.jdbc().query(
                "select * from boundary_dispute where id = ?", (rs, i) -> map(rs), disputeId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("DISPUTE_NOT_FOUND", "boundary dispute not found: " + disputeId);
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- ruling

    @Transactional
    public DisputeDtos.DisputeView rule(long disputeId, DisputeDtos.RuleRequest req) {
        DisputeRow dispute = require(disputeId);
        if (!"OPEN".equals(dispute.status()) && !"WITHDRAWN".equals(dispute.status())) {
            throw ApiException.conflict("DISPUTE_NOT_OPEN",
                    "dispute " + disputeId + " is " + dispute.status()
                            + "; withdraw the current ruling before issuing another one");
        }
        String ruling = req == null ? null : req.ruling();
        if (ruling == null || !RULINGS.contains(ruling.trim().toUpperCase())) {
            throw ApiException.badRequest("INVALID_RULING",
                    "ruling must be one of " + RULINGS);
        }
        ruling = ruling.trim().toUpperCase();
        String decidedBy = req.decidedBy() == null || req.decidedBy().isBlank()
                ? "dispute-officer" : req.decidedBy().trim();
        Long snapshotId = latestSnapshotId(dispute.caseId());
        Double retainedArea = retainedAreaHa(dispute);
        db.insert("""
                        insert into boundary_dispute_ruling
                            (dispute_id, action, ruling, decided_by, note, retained_area_ha,
                             retained_snapshot_id)
                        values (?, 'RULE', ?, ?, ?, ?, ?)
                        """,
                disputeId, ruling, decidedBy, req.note(), retainedArea, snapshotId);
        db.jdbc().update("""
                        update boundary_dispute
                           set status = 'RULED', ruling = ?, ruled_by = ?, ruled_at = now(),
                               ruling_note = ?
                         where id = ?
                        """, ruling, decidedBy, req.note(), disputeId);
        return getView(disputeId);
    }

    /**
     * Withdraw the current ruling. The ruling row is retained; the dispute returns to OPEN and the
     * contested pixels are back in the arbitration queue on the next sampling run.
     */
    @Transactional
    public DisputeDtos.DisputeView withdraw(long disputeId, DisputeDtos.WithdrawRequest req) {
        DisputeRow dispute = require(disputeId);
        if (!"RULED".equals(dispute.status())) {
            throw ApiException.conflict("DISPUTE_NOT_RULED",
                    "only a RULED dispute can be withdrawn; dispute " + disputeId + " is " + dispute.status());
        }
        String withdrawnBy = req == null || req.withdrawnBy() == null || req.withdrawnBy().isBlank()
                ? "dispute-officer" : req.withdrawnBy().trim();
        Long snapshotId = latestSnapshotId(dispute.caseId());
        Double retainedArea = retainedAreaHa(dispute);
        db.insert("""
                        insert into boundary_dispute_ruling
                            (dispute_id, action, ruling, decided_by, note, retained_area_ha,
                             retained_snapshot_id)
                        values (?, 'WITHDRAW', ?, ?, ?, ?, ?)
                        """,
                disputeId, dispute.ruling(), withdrawnBy,
                req == null ? null : req.note(), retainedArea, snapshotId);
        db.jdbc().update("""
                        update boundary_dispute
                           set status = 'WITHDRAWN', withdrawn_by = ?, withdrawn_at = now(),
                               withdrawal_note = ?
                         where id = ?
                        """, withdrawnBy, req == null ? null : req.note(), disputeId);
        // Contested pixels attributed or excluded under the old ruling return to the queue; the
        // next sampling run will re-decide them against the still-frozen intake geometry.
        allocator.requeueCellsForDispute(disputeId);
        return getView(disputeId);
    }

    // --------------------------------------------------------- re-sampling

    /**
     * Open a follow-up review. A new sampling run is always created — even when a previous review
     * exists — and the prior ruling plus the original area result are copied onto the request.
     */
    @Transactional
    public DisputeDtos.ReviewRequestView requestReview(long disputeId, DisputeDtos.ReviewRequest req) {
        DisputeRow dispute = require(disputeId);
        if (dispute.caseId() == null) {
            throw ApiException.unprocessable("DISPUTE_WITHOUT_CASE",
                    "dispute " + disputeId + " is not attached to a claim case; no sampling run can be created");
        }
        Long priorRulingId = db.jdbc().query("""
                        select id from boundary_dispute_ruling where dispute_id = ?
                         order by decided_at desc, id desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, disputeId);
        Long priorSnapshotId = latestSnapshotId(dispute.caseId());
        Double priorArea = retainedAreaHa(dispute);
        Long parentRunId = latestRunId(dispute.caseId());
        String requestedBy = req == null || req.requestedBy() == null || req.requestedBy().isBlank()
                ? "dispute-officer" : req.requestedBy().trim();
        String reason = req == null || req.reason() == null
                ? "re-sampling after dispute desk review" : req.reason();
        long runId = runs.queueDisputeResampling(dispute.caseId(), parentRunId,
                "dispute " + disputeId + ": " + reason);
        long requestId = db.insert("""
                        insert into boundary_review_request
                            (dispute_id, case_id, sampling_run_id, status, requested_by, reason,
                             prior_ruling_id, prior_area_ha, prior_snapshot_id)
                        values (?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?)
                        """,
                disputeId, dispute.caseId(), runId, requestedBy, reason,
                priorRulingId, priorArea, priorSnapshotId);
        return reviewRequest(requestId);
    }

    public List<DisputeDtos.ReviewRequestView> reviewRequests(long disputeId) {
        require(disputeId);
        List<Long> ids = db.jdbc().query(
                "select id from boundary_review_request where dispute_id = ? order by id desc",
                (rs, i) -> rs.getLong(1), disputeId);
        return ids.stream().map(this::reviewRequest).toList();
    }

    public DisputeDtos.ReviewRequestView reviewRequest(long reviewRequestId) {
        List<DisputeDtos.ReviewRequestView> rows = db.jdbc().query("""
                        select * from boundary_review_request where id = ?
                        """, (rs, i) -> {
            long id = rs.getLong("id");
            long runId = rs.getLong("sampling_run_id");
            return new DisputeDtos.ReviewRequestView(id, rs.getLong("dispute_id"),
                    rs.getLong("case_id"), runId, rs.getString("status"),
                    rs.getString("requested_by"), Times.format(Db.instant(rs, "requested_at")),
                    rs.getString("reason"), (Long) rs.getObject("prior_ruling_id"),
                    rs.getBigDecimal("prior_area_ha") == null ? null
                            : rs.getBigDecimal("prior_area_ha").doubleValue(),
                    (Long) rs.getObject("prior_snapshot_id"),
                    Times.format(Db.instant(rs, "completed_at")));
        }, reviewRequestId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("REVIEW_REQUEST_NOT_FOUND",
                    "review request not found: " + reviewRequestId);
        }
        DisputeDtos.ReviewRequestView view = rows.get(0);
        syncReviewRequest(view, reviewRequestId);
        return view;
    }

    /** Reflect the sampling run's terminal state onto the review request when queried. */
    private void syncReviewRequest(DisputeDtos.ReviewRequestView view, long reviewRequestId) {
        if ("COMPLETED".equals(view.status()) || "FAILED".equals(view.status())) {
            return;
        }
        String runStatus = db.jdbc().queryForObject(
                "select status from feature_run where id = ?", String.class, view.samplingRunId());
        String target = switch (runStatus) {
            case "SUCCEEDED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "RUNNING" -> "RUNNING";
            default -> view.status();
        };
        if (!target.equals(view.status())) {
            if ("COMPLETED".equals(target)) {
                db.jdbc().update(
                        "update boundary_review_request set status = ?, completed_at = now() where id = ?",
                        target, reviewRequestId);
            } else {
                db.jdbc().update("update boundary_review_request set status = ? where id = ?",
                        target, reviewRequestId);
            }
        }
    }

    // ----------------------------------------------------------------- views

    private DisputeDtos.DisputeView toView(DisputeRow row) {
        String caseNo = null;
        if (row.caseId() != null) {
            caseNo = db.jdbc().queryForObject(
                    "select case_no from claim_case where id = ?", String.class, row.caseId());
        }
        List<DisputeDtos.RulingView> rulings = db.jdbc().query("""
                        select id, action, ruling, decided_by, decided_at, note, retained_area_ha,
                               retained_snapshot_id, sampling_run_id
                          from boundary_dispute_ruling where dispute_id = ? order by id
                        """,
                (rs, i) -> new DisputeDtos.RulingView(rs.getLong("id"), rs.getString("action"),
                        rs.getString("ruling"), rs.getString("decided_by"),
                        Times.format(Db.instant(rs, "decided_at")), rs.getString("note"),
                        rs.getBigDecimal("retained_area_ha") == null ? null
                                : rs.getBigDecimal("retained_area_ha").doubleValue(),
                        (Long) rs.getObject("retained_snapshot_id"),
                        (Long) rs.getObject("sampling_run_id")),
                row.id());
        List<DisputeDtos.ReviewRequestView> reviews = reviewRequests(row.id());
        Long pending = db.jdbc().queryForObject("""
                        select count(*) from pixel_allocation
                         where dispute_id = ? and allocation_status = 'PENDING_ARBITRATION'
                        """, Long.class, row.id());
        return new DisputeDtos.DisputeView(row.id(), row.disputeNo(), row.caseId(), caseNo,
                row.parcelId(), row.overlapAnalysisId(), row.overlapPairId(),
                row.boundaryA(), row.boundaryB(), row.overlapAreaHa(), row.tolerancePct(),
                row.status(), row.reason(), row.openedBy(), Times.format(row.openedAt()),
                row.ruling(), row.ruledBy(), Times.format(row.ruledAt()), row.rulingNote(),
                row.withdrawnBy(), Times.format(row.withdrawnAt()), row.withdrawalNote(),
                Times.format(row.closedAt()), rulings, reviews,
                pending == null ? 0 : pending);
    }

    private Long latestSnapshotId(Long caseId) {
        if (caseId == null) {
            return null;
        }
        return db.jdbc().query("""
                        select id from scoring_snapshot where case_id = ?
                         order by created_at desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, caseId);
    }

    private Long latestRunId(long caseId) {
        return db.jdbc().query("""
                        select id from feature_run where case_id = ? order by id desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, caseId);
    }

    /**
     * The area result that was in force when the dispute was ruled: the intake-frozen geometry area
     * (never the newest registry area).
     */
    private Double retainedAreaHa(DisputeRow dispute) {
        if (dispute.caseId() != null) {
            GeometryFreezeService.FreezeRow freeze = freezer.findByCaseId(dispute.caseId());
            if (freeze != null) {
                return freeze.areaHa();
            }
        }
        return dispute.overlapAreaHa();
    }

    private DisputeRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DisputeRow(rs.getLong("id"), rs.getString("dispute_no"),
                (Long) rs.getObject("case_id"), rs.getLong("parcel_id"),
                (Long) rs.getObject("overlap_analysis_id"), (Long) rs.getObject("overlap_pair_id"),
                rs.getLong("subject_boundary_version_id_a"),
                rs.getLong("subject_boundary_version_id_b"),
                rs.getBigDecimal("overlap_area_ha").doubleValue(),
                rs.getBigDecimal("tolerance_pct").doubleValue(),
                rs.getString("status"), rs.getString("reason"), rs.getString("opened_by"),
                Db.instant(rs, "opened_at"), rs.getString("ruling"), rs.getString("ruled_by"),
                Db.instant(rs, "ruled_at"), rs.getString("ruling_note"),
                rs.getString("withdrawn_by"), Db.instant(rs, "withdrawn_at"),
                rs.getString("withdrawal_note"), Db.instant(rs, "closed_at"));
    }
}
