package com.agri.pit.boundary;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.tenure.TenureVersionService;
import com.agri.pit.tenure.TenureVersionService.TenureRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Case geometry freezer.
 * <p>
 * At intake the case's eligible geometry version — the boundary already APPROVED and visible at
 * the scoring cutoff — is copied row-by-row into {@code case_geometry_freeze}, including a full
 * GeoJSON snapshot. Every later scoring / replay / re-sampling run reads this frozen row rather
 * than the current registry, so concurrent tenure approvals and warehouse backfills can never
 * silently move the area a historical case was scored on. Frozen rows are database-enforced
 * immutable.
 */
@Service
public class GeometryFreezeService {

    private final Db db;
    private final TenureVersionService tenure;

    public GeometryFreezeService(Db db, TenureVersionService tenure) {
        this.db = db;
        this.tenure = tenure;
    }

    public record CaseRef(long caseId, String caseNo, long parcelId, long boundaryVersionId,
                          Instant scoringCutoff) {
    }

    public record FreezeRow(long id, long caseId, String caseNo, Long tenureVersionId,
                            long boundaryVersionId, String ownerName, String boundarySource,
                            String sourceDocNo, Instant scoringCutoff, JsonNode geometrySnapshot,
                            double areaHa, double policyTolerancePct, Instant frozenAt, String note) {
    }

    @Transactional
    public long freezeForCase(CaseRef caseRef, Double policyTolerancePct, String note) {
        List<Long> existing = db.jdbc().query(
                "select id from case_geometry_freeze where case_id = ? for update",
                (rs, i) -> rs.getLong(1), caseRef.caseId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OverlapAnalysisService.BoundaryVersionRow boundary = boundary(caseRef.boundaryVersionId());
        if (boundary.parcelId() != caseRef.parcelId()) {
            throw ApiException.badRequest("BOUNDARY_VERSION_NOT_FOR_PARCEL",
                    "boundary " + boundary.id() + " does not belong to the case parcel " + caseRef.parcelId());
        }
        // Only geometry already visible at intake may be frozen. A deliberately late lock is still
        // recorded (the leakage detector blocks scoring), but never silently replaced by a newer map.
        TenureRow tenureRow = tenure.approvedTenureAsOf(caseRef.parcelId(), caseRef.scoringCutoff());
        Long tenureVersionId = null;
        String ownerName = null;
        String boundarySource = null;
        String sourceDocNo = null;
        if (tenureRow != null) {
            tenureVersionId = tenureRow.id();
            ownerName = tenureRow.ownerName();
            boundarySource = tenureRow.boundarySource();
            sourceDocNo = tenureRow.sourceDocNo();
            if (tenureRow.boundaryVersionId() != caseRef.boundaryVersionId()) {
                note = joinNote(note, "case locks boundary v" + boundary.versionNo()
                        + " while the tenure valid at intake references boundary v"
                        + tenureBoundaryVersionNo(tenureRow.boundaryVersionId()));
            }
        } else {
            note = joinNote(note, "no approved tenure version visible at the cutoff; boundary locked "
                    + "directly from the parcel registry");
        }
        double tolerance = policyTolerancePct == null
                ? OverlapAnalysisService.DEFAULT_TOLERANCE_PCT : policyTolerancePct;
        return db.insert("""
                        insert into case_geometry_freeze
                            (case_id, tenure_version_id, boundary_version_id, owner_name, boundary_source,
                             source_doc_no, scoring_cutoff, geometry_snapshot, area_ha,
                             policy_tolerance_pct, note)
                        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """,
                caseRef.caseId(), tenureVersionId, boundary.id(), ownerName, boundarySource, sourceDocNo,
                Db.timestamp(caseRef.scoringCutoff()), db.jsonArg(boundary.geometry()),
                boundary.areaHa(), tolerance, note);
    }

    public FreezeRow requireByCaseId(long caseId) {
        List<FreezeRow> rows = query("""
                        select f.*, c.case_no from case_geometry_freeze f
                          join claim_case c on c.id = f.case_id
                         where f.case_id = ?
                        """, caseId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("GEOMETRY_FREEZE_NOT_FOUND",
                    "no frozen geometry for case " + caseId);
        }
        return rows.get(0);
    }

    public FreezeRow findByCaseId(long caseId) {
        List<FreezeRow> rows = query("""
                        select f.*, c.case_no from case_geometry_freeze f
                          join claim_case c on c.id = f.case_id
                         where f.case_id = ?
                        """, caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<FreezeRow> findByFreezeId(long freezeId) {
        return query("""
                        select f.*, c.case_no from case_geometry_freeze f
                          join claim_case c on c.id = f.case_id
                         where f.id = ?
                        """, freezeId);
    }

    public BoundaryDtos.GeometryFreezeView toView(FreezeRow row) {
        Integer tenureVersionNo = null;
        if (row.tenureVersionId() != null) {
            tenureVersionNo = db.jdbc().queryForObject(
                    "select version_no from parcel_tenure_version where id = ?",
                    Integer.class, row.tenureVersionId());
        }
        int boundaryVersionNo = db.jdbc().queryForObject(
                "select version_no from parcel_boundary_version where id = ?",
                Integer.class, row.boundaryVersionId());
        return new BoundaryDtos.GeometryFreezeView(row.id(), row.caseId(), row.caseNo(),
                row.tenureVersionId(), tenureVersionNo, row.boundaryVersionId(), boundaryVersionNo,
                row.ownerName(), row.boundarySource(), row.sourceDocNo(),
                Times.format(row.scoringCutoff()), row.areaHa(), row.policyTolerancePct(),
                row.geometrySnapshot(), Times.format(row.frozenAt()), row.note());
    }

    private OverlapAnalysisService.BoundaryVersionRow boundary(long boundaryVersionId) {
        List<OverlapAnalysisService.BoundaryVersionRow> rows = db.jdbc().query("""
                        select b.id, b.parcel_id, p.parcel_code, b.version_no, b.geometry_geojson,
                               b.area_ha, b.valid_from, b.ingested_at
                          from parcel_boundary_version b join parcel p on p.id = b.parcel_id
                         where b.id = ?
                        """,
                (rs, i) -> new OverlapAnalysisService.BoundaryVersionRow(rs.getLong("id"),
                        rs.getLong("parcel_id"),
                        rs.getString("parcel_code"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")),
                        rs.getBigDecimal("area_ha").doubleValue(),
                        rs.getObject("valid_from", java.time.LocalDate.class),
                        Db.instant(rs, "ingested_at")),
                boundaryVersionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("BOUNDARY_NOT_FOUND",
                    "boundary version not found: " + boundaryVersionId);
        }
        return rows.get(0);
    }

    private Integer tenureBoundaryVersionNo(long boundaryVersionId) {
        return db.jdbc().queryForObject(
                "select version_no from parcel_boundary_version where id = ?",
                Integer.class, boundaryVersionId);
    }

    private List<FreezeRow> query(String sql, Object... args) {
        return db.jdbc().query(sql, (rs, i) -> new FreezeRow(
                rs.getLong("id"), rs.getLong("case_id"), rs.getString("case_no"),
                (Long) rs.getObject("tenure_version_id"), rs.getLong("boundary_version_id"),
                rs.getString("owner_name"), rs.getString("boundary_source"),
                rs.getString("source_doc_no"), Db.instant(rs, "scoring_cutoff"),
                db.readTree(rs.getString("geometry_snapshot")),
                rs.getBigDecimal("area_ha").doubleValue(),
                rs.getBigDecimal("policy_tolerance_pct").doubleValue(),
                Db.instant(rs, "frozen_at"), rs.getString("note")), args);
    }

    private static String joinNote(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return a + "; " + b;
    }
}
