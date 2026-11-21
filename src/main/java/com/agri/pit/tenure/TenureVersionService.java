package com.agri.pit.tenure;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Parcel tenure version library.
 * <p>
 * Every tenure record keeps the owner, the boundary source, the effective dates and its own
 * approval state. A transfer or re-survey appends a new version; the predecessor is never
 * overwritten. Case intake may only resolve an APPROVED version whose geometry was visible at
 * the scoring cutoff, so a concurrent tenure approval / backfill cannot change what an already
 * accepted case is scored against.
 */
@Service
public class TenureVersionService {

    public static final List<String> TENURE_TYPES =
            List.of("SELF_OPERATED", "CONTRACTED", "TRANSFERRED", "COLLECTIVE");
    public static final List<String> BOUNDARY_SOURCES =
            List.of("REPORTED_SURVEY", "AGRI_REGISTRY", "RE_SURVEY", "TRANSFER_DEED", "MANUAL_ADJUSTMENT");

    private final Db db;

    public TenureVersionService(Db db) {
        this.db = db;
    }

    public record TenureRow(long id, long parcelId, int versionNo, String ownerName, String ownerIdCode,
                            String tenureType, long boundaryVersionId, String boundarySource,
                            String sourceDocNo, java.time.LocalDate effectiveFrom,
                            java.time.LocalDate effectiveTo, String status, String approvedBy,
                            Instant approvedAt, Instant observedAt, Instant ingestedAt,
                            Long supersedesTenureId, String note) {
    }

    @Transactional
    public long createTenure(TenureDtos.CreateTenureRequest req) {
        requireParcel(req.parcelId());
        requireBoundary(req.parcelId(), req.boundaryVersionId());
        if (req.ownerName() == null || req.ownerName().isBlank()) {
            throw ApiException.badRequest("OWNER_REQUIRED", "ownerName is required");
        }
        if (req.effectiveFrom() == null) {
            throw ApiException.badRequest("EFFECTIVE_FROM_REQUIRED", "effectiveFrom is required");
        }
        if (req.observedAt() == null || req.ingestedAt() == null) {
            throw ApiException.badRequest("TENURE_CLOCKS_REQUIRED",
                    "observedAt and ingestedAt are required for tenure versions");
        }
        String tenureType = normalize(req.tenureType(), TENURE_TYPES, "tenureType");
        String boundarySource = normalize(req.boundarySource(), BOUNDARY_SOURCES, "boundarySource");
        int versionNo = req.versionNo() != null ? req.versionNo()
                : db.jdbc().queryForObject(
                        "select coalesce(max(version_no), 0) + 1 from parcel_tenure_version where parcel_id = ?",
                        Integer.class, req.parcelId());
        Integer dup = db.jdbc().queryForObject(
                "select count(*) from parcel_tenure_version where parcel_id = ? and version_no = ?",
                Integer.class, req.parcelId(), versionNo);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("TENURE_VERSION_EXISTS",
                    "tenure version " + versionNo + " already exists for parcel " + req.parcelId());
        }
        Long supersedes = req.supersedesTenureId();
        if (supersedes == null) {
            List<Long> latest = db.jdbc().query("""
                            select id from parcel_tenure_version where parcel_id = ?
                             order by version_no desc limit 1
                            """, (rs, i) -> rs.getLong(1), req.parcelId());
            supersedes = latest.isEmpty() ? null : latest.get(0);
        }
        return db.insert("""
                        insert into parcel_tenure_version
                            (parcel_id, version_no, owner_name, owner_id_code, tenure_type,
                             boundary_version_id, boundary_source, source_doc_no, effective_from, effective_to,
                             status, observed_at, ingested_at, supersedes_tenure_id, note)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?, ?)
                        """,
                req.parcelId(), versionNo, req.ownerName().trim(), req.ownerIdCode(), tenureType,
                req.boundaryVersionId(), boundarySource, req.sourceDocNo(),
                req.effectiveFrom(), req.effectiveTo(),
                Db.timestamp(req.observedAt()), Db.timestamp(req.ingestedAt()),
                supersedes, req.note());
    }

    @Transactional
    public long approve(long tenureVersionId, String approvedBy, String note) {
        return approve(tenureVersionId, approvedBy, note, Instant.now());
    }

    @Transactional
    public long approve(long tenureVersionId, String approvedBy, String note, Instant approvedAt) {
        TenureRow row = requireTenure(tenureVersionId);
        if (!"PENDING_APPROVAL".equals(row.status())) {
            throw ApiException.conflict("TENURE_NOT_PENDING",
                    "tenure version " + tenureVersionId + " is " + row.status() + " and cannot be approved");
        }
        db.jdbc().update("""
                        update parcel_tenure_version
                           set status = 'APPROVED', approved_by = ?, approved_at = ?,
                               note = coalesce(?, note)
                         where id = ?
                        """,
                approvedBy == null || approvedBy.isBlank() ? "registry-admin" : approvedBy.trim(),
                Db.timestamp(approvedAt), note, tenureVersionId);
        // Earlier approved versions keep their APPROVED state: as-of queries must still resolve a
        // historical case to the tenure governing it at intake. "Currentness" is derived from
        // approved_at / effective dates rather than by mutating the predecessor's status.
        return tenureVersionId;
    }

    @Transactional
    public long reject(long tenureVersionId, String decidedBy, String note) {
        TenureRow row = requireTenure(tenureVersionId);
        if (!"PENDING_APPROVAL".equals(row.status())) {
            throw ApiException.conflict("TENURE_NOT_PENDING",
                    "tenure version " + tenureVersionId + " is " + row.status() + " and cannot be rejected");
        }
        db.jdbc().update("""
                        update parcel_tenure_version
                           set status = 'REJECTED', approved_by = ?, note = coalesce(?, note)
                         where id = ?
                        """,
                decidedBy == null || decidedBy.isBlank() ? "registry-admin" : decidedBy.trim(),
                note, tenureVersionId);
        return tenureVersionId;
    }

    public List<TenureRow> listForParcel(long parcelId) {
        requireParcel(parcelId);
        return db.jdbc().query("""
                        select * from parcel_tenure_version where parcel_id = ? order by version_no
                        """, (rs, i) -> map(rs), parcelId);
    }

    public TenureRow requireTenure(long tenureVersionId) {
        List<TenureRow> rows = db.jdbc().query(
                "select * from parcel_tenure_version where id = ?", (rs, i) -> map(rs), tenureVersionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("TENURE_NOT_FOUND",
                    "tenure version not found: " + tenureVersionId);
        }
        return rows.get(0);
    }

    public TenureRow requireApprovedTenure(long tenureVersionId) {
        TenureRow row = requireTenure(tenureVersionId);
        if (!"APPROVED".equals(row.status())) {
            throw ApiException.unprocessable("TENURE_NOT_APPROVED",
                    "tenure version " + tenureVersionId + " is " + row.status()
                            + "; only APPROVED tenure may back a case geometry freeze");
        }
        return row;
    }

    /**
     * Resolve the tenure that governed a parcel at a given instant: APPROVED, with both the
     * approval and the record itself already visible ({@code approved_at <= asOf} and
     * {@code ingested_at <= asOf}). Returns null when no approved tenure existed yet.
     */
    public TenureRow approvedTenureAsOf(long parcelId, Instant asOf) {
        List<TenureRow> rows = db.jdbc().query("""
                        select * from parcel_tenure_version
                         where parcel_id = ? and status = 'APPROVED'
                           and approved_at <= ? and ingested_at <= ?
                         order by approved_at desc, version_no desc limit 1
                        """, (rs, i) -> map(rs), parcelId,
                Db.timestamp(asOf), Db.timestamp(asOf));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Latest APPROVED tenure regardless of time, used by the re-survey review path. */
    public TenureRow latestApprovedTenure(long parcelId) {
        List<TenureRow> rows = db.jdbc().query("""
                        select * from parcel_tenure_version
                         where parcel_id = ? and status = 'APPROVED'
                         order by version_no desc limit 1
                        """, (rs, i) -> map(rs), parcelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireParcel(long parcelId) {
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from parcel where id = ?", Integer.class, parcelId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("PARCEL_NOT_FOUND", "parcel not found: " + parcelId);
        }
    }

    private void requireBoundary(long parcelId, long boundaryVersionId) {
        Integer exists = db.jdbc().queryForObject("""
                        select count(*) from parcel_boundary_version
                         where id = ? and parcel_id = ?
                        """, Integer.class, boundaryVersionId, parcelId);
        if (exists == null || exists == 0) {
            throw ApiException.badRequest("BOUNDARY_VERSION_NOT_FOR_PARCEL",
                    "boundary version " + boundaryVersionId + " does not belong to parcel " + parcelId);
        }
    }

    private static String normalize(String value, List<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("FIELD_REQUIRED", field + " is required");
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw ApiException.badRequest("INVALID_" + field.toUpperCase(),
                    field + " must be one of " + allowed);
        }
        return normalized;
    }

    private TenureRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TenureRow(rs.getLong("id"), rs.getLong("parcel_id"), rs.getInt("version_no"),
                rs.getString("owner_name"), rs.getString("owner_id_code"), rs.getString("tenure_type"),
                rs.getLong("boundary_version_id"), rs.getString("boundary_source"),
                rs.getString("source_doc_no"),
                rs.getObject("effective_from", java.time.LocalDate.class),
                rs.getObject("effective_to", java.time.LocalDate.class),
                rs.getString("status"), rs.getString("approved_by"), Db.instant(rs, "approved_at"),
                Db.instant(rs, "observed_at"), Db.instant(rs, "ingested_at"),
                (Long) rs.getObject("supersedes_tenure_id"), rs.getString("note"));
    }

    public TenureDtos.TenureView toView(TenureRow row) {
        Integer boundaryVersionNo = db.jdbc().queryForObject(
                "select version_no from parcel_boundary_version where id = ?",
                Integer.class, row.boundaryVersionId());
        String parcelCode = db.jdbc().queryForObject(
                "select parcel_code from parcel where id = ?", String.class, row.parcelId());
        return new TenureDtos.TenureView(row.id(), row.parcelId(), parcelCode, row.versionNo(),
                row.ownerName(), row.ownerIdCode(), row.tenureType(), row.boundaryVersionId(),
                boundaryVersionNo, row.boundarySource(), row.sourceDocNo(),
                row.effectiveFrom(), row.effectiveTo(), row.status(), row.approvedBy(),
                Times.format(row.approvedAt()), Times.format(row.observedAt()),
                Times.format(row.ingestedAt()), row.supersedesTenureId(), row.note());
    }
}
