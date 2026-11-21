package com.agri.pit.boundary;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.GeoUtil;
import com.agri.pit.common.Times;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boundary overlap analyzer.
 * <p>
 * Computes the overlap graph for a parcel: every old-vs-new boundary version pair of the parcel
 * itself, plus pairs against neighbouring parcels' boundaries. For each pair it stores the
 * intersection area, each side's overlap percentage and whether the policy tolerance is exceeded.
 * Geometries live as GeoJSON in stock PostgreSQL (no PostGIS), so all intersections are computed
 * in-process with JTS.
 */
@Service
public class OverlapAnalysisService {

    public static final double DEFAULT_TOLERANCE_PCT = 5.0d;

    private final Db db;
    private final GeoUtil geo;

    public OverlapAnalysisService(Db db, GeoUtil geo) {
        this.db = db;
        this.geo = geo;
    }

    public record BoundaryVersionRow(long id, long parcelId, String parcelCode, int versionNo,
                                     JsonNode geometry, double areaHa, LocalDate validFrom,
                                     Instant ingestedAt) {
    }

    public record OverlapMetric(long boundaryA, long boundaryB, double intersectionHa,
                                double pctOfA, double pctOfB, boolean exceeds, Geometry intersection) {
    }

    // ------------------------------------------------------------- persistence

    @Transactional
    public long analyzeParcel(long parcelId, Double tolerancePct, String requestedScope, String createdBy) {
        requireParcel(parcelId);
        double tolerance = tolerancePct == null ? DEFAULT_TOLERANCE_PCT : tolerancePct;
        if (tolerance < 0 || tolerance > 100) {
            throw ApiException.badRequest("INVALID_TOLERANCE",
                    "tolerancePct must be within [0, 100]");
        }
        String scope = normalizeScope(requestedScope);
        List<BoundaryVersionRow> own = boundaryVersions(parcelId);
        if (own.isEmpty()) {
            throw ApiException.unprocessable("NO_BOUNDARY_VERSION",
                    "parcel " + parcelId + " has no boundary version to analyze");
        }
        List<BoundaryVersionRow> neighbours = "PARCEL_VERSIONS".equals(scope)
                ? List.of() : neighbourBoundaries(parcelId);

        ObjectNode summary = db.mapper().createObjectNode();
        long analysisId = db.insert("""
                        insert into boundary_overlap_analysis
                            (parcel_id, base_boundary_version_id, comparison_scope, policy_tolerance_pct,
                             status, summary, created_by)
                        values (?, ?, ?, ?, 'COMPLETED', '{}'::jsonb, ?)
                        """,
                parcelId, own.get(own.size() - 1).id(), scope, tolerance,
                createdBy == null || createdBy.isBlank() ? "system" : createdBy.trim());

        int pairCount = 0;
        int exceeding = 0;
        double maxOverlapHa = 0d;

        // 1) old vs new versions of the same parcel
        if (!"NEIGHBOURS".equals(scope)) {
            for (int i = 0; i < own.size(); i++) {
                for (int j = i + 1; j < own.size(); j++) {
                    OverlapMetric metric = metric(own.get(i), own.get(j), tolerance);
                    persistPair(analysisId, own.get(i), own.get(j), true, metric);
                    pairCount++;
                    exceeding += metric.exceeds() ? 1 : 0;
                    maxOverlapHa = Math.max(maxOverlapHa, metric.intersectionHa());
                }
            }
        }

        // 2) each own version vs every neighbouring parcel version that intersects it
        if (!neighbours.isEmpty()) {
            for (BoundaryVersionRow a : own) {
                Geometry ga = geo.parse(a.geometry());
                for (BoundaryVersionRow b : neighbours) {
                    Geometry gb = geo.parse(b.geometry());
                    if (!ga.intersects(gb)) {
                        continue;
                    }
                    OverlapMetric metric = metric(a, b, tolerance);
                    if (metric.intersectionHa() <= 0d) {
                        continue;
                    }
                    persistPair(analysisId, a, b, false, metric);
                    pairCount++;
                    exceeding += metric.exceeds() ? 1 : 0;
                    maxOverlapHa = Math.max(maxOverlapHa, metric.intersectionHa());
                }
            }
        }

        summary.put("pairCount", pairCount);
        summary.put("exceedingTolerancePairs", exceeding);
        summary.put("maxOverlapAreaHa", round4(maxOverlapHa));
        summary.put("tolerancePct", tolerance);
        db.jdbc().update("update boundary_overlap_analysis set summary = ?::jsonb where id = ?",
                db.toJson(summary), analysisId);
        return analysisId;
    }

    private void persistPair(long analysisId, BoundaryVersionRow a, BoundaryVersionRow b,
                             boolean sameParcel, OverlapMetric metric) {
        ObjectNode intersectionGeo = metric.intersection() == null ? null
                : geo.toGeoJson(metric.intersection());
        db.insert("""
                        insert into boundary_overlap_pair
                            (analysis_id, parcel_id_a, boundary_version_id_a, parcel_id_b,
                             boundary_version_id_b, same_parcel, intersection_area_ha, area_a_ha, area_b_ha,
                             overlap_pct_of_a, overlap_pct_of_b, exceeds_tolerance, intersection_geojson)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        on conflict (analysis_id, boundary_version_id_a, boundary_version_id_b) do nothing
                        """,
                analysisId, a.parcelId(), a.id(), b.parcelId(), b.id(), sameParcel,
                round4(metric.intersectionHa()), round4(a.areaHa()), round4(b.areaHa()),
                round4(metric.pctOfA()), round4(metric.pctOfB()), metric.exceeds(),
                intersectionGeo == null ? null : db.jsonArg(intersectionGeo));
    }

    // ------------------------------------------------------------- on-demand metric

    /** Compute the overlap metric of two stored boundary versions (used by the pixel allocator). */
    public OverlapMetric overlapOf(long boundaryVersionA, long boundaryVersionB, double tolerancePct) {
        BoundaryVersionRow a = boundaryVersion(boundaryVersionA);
        BoundaryVersionRow b = boundaryVersion(boundaryVersionB);
        return metric(a, b, tolerancePct);
    }

    public OverlapMetric metric(BoundaryVersionRow a, BoundaryVersionRow b, double tolerancePct) {
        Geometry ga = geo.parse(a.geometry());
        Geometry gb = geo.parse(b.geometry());
        if (!ga.intersects(gb)) {
            return new OverlapMetric(a.id(), b.id(), 0d, 0d, 0d, false, null);
        }
        Geometry intersection = ga.intersection(gb);
        double intersectionHa = geo.areaHectares(intersection);
        double pctOfA = a.areaHa() <= 0d ? 0d : intersectionHa / a.areaHa() * 100d;
        double pctOfB = b.areaHa() <= 0d ? 0d : intersectionHa / b.areaHa() * 100d;
        boolean exceeds = Math.max(pctOfA, pctOfB) > tolerancePct;
        return new OverlapMetric(a.id(), b.id(), intersectionHa, pctOfA, pctOfB, exceeds, intersection);
    }

    // ------------------------------------------------------------------ graph

    public BoundaryDtos.OverlapGraph graphForParcel(long parcelId, Double tolerancePct) {
        requireParcel(parcelId);
        List<Map<String, Object>> analysisRows = db.jdbc().queryForList("""
                        select id, policy_tolerance_pct from boundary_overlap_analysis
                         where parcel_id = ? order by created_at desc limit 1
                        """, parcelId);
        if (analysisRows.isEmpty()) {
            throw ApiException.notFound("OVERLAP_ANALYSIS_NOT_FOUND",
                    "run POST /api/boundary/parcels/" + parcelId + "/overlap-analyses first");
        }
        long analysisId = ((Number) analysisRows.get(0).get("id")).longValue();
        double tolerance = ((Number) analysisRows.get(0).get("policy_tolerance_pct")).doubleValue();
        List<BoundaryVersionRow> own = boundaryVersions(parcelId);
        Map<Long, BoundaryDtos.BoundaryNode> nodes = new LinkedHashMap<>();
        for (BoundaryVersionRow row : own) {
            nodes.put(row.id(), node(row));
        }
        List<BoundaryDtos.OverlapEdge> edges = new ArrayList<>();
        db.jdbc().query("""
                        select * from boundary_overlap_pair where analysis_id = ? order by id
                        """, rs -> {
            long aId = rs.getLong("boundary_version_id_a");
            long bId = rs.getLong("boundary_version_id_b");
            BoundaryVersionRow a = boundaryVersion(aId);
            BoundaryVersionRow b = boundaryVersion(bId);
            nodes.putIfAbsent(aId, node(a));
            nodes.putIfAbsent(bId, node(b));
            Long openDispute = db.jdbc().query("""
                            select id from boundary_dispute
                             where status in ('OPEN','RULED')
                               and ((subject_boundary_version_id_a = ? and subject_boundary_version_id_b = ?)
                                 or (subject_boundary_version_id_a = ? and subject_boundary_version_id_b = ?))
                             order by id desc limit 1
                            """, rs2 -> rs2.next() ? rs2.getLong(1) : null, aId, bId, bId, aId);
            edges.add(new BoundaryDtos.OverlapEdge(rs.getLong("id"), analysisId, node(a), node(b),
                    rs.getBoolean("same_parcel"), rs.getBigDecimal("intersection_area_ha").doubleValue(),
                    rs.getBigDecimal("overlap_pct_of_a").doubleValue(),
                    rs.getBigDecimal("overlap_pct_of_b").doubleValue(),
                    rs.getBoolean("exceeds_tolerance"),
                    db.readTree(rs.getString("intersection_geojson")), openDispute));
        }, analysisId);
        return new BoundaryDtos.OverlapGraph(parcelId, own.isEmpty() ? String.valueOf(parcelId) : own.get(0).parcelCode(),
                tolerance, new ArrayList<>(nodes.values()), edges);
    }

    public Map<String, Object> getAnalysis(long analysisId) {
        List<Map<String, Object>> rows = db.jdbc().query("""
                        select a.*, p.parcel_code
                          from boundary_overlap_analysis a join parcel p on p.id = a.parcel_id
                         where a.id = ?
                        """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("parcelId", rs.getLong("parcel_id"));
            row.put("parcelCode", rs.getString("parcel_code"));
            row.put("baseBoundaryVersionId", rs.getLong("base_boundary_version_id"));
            row.put("comparisonScope", rs.getString("comparison_scope"));
            row.put("policyTolerancePct", rs.getBigDecimal("policy_tolerance_pct").doubleValue());
            row.put("status", rs.getString("status"));
            row.put("summary", db.readTree(rs.getString("summary")));
            row.put("createdBy", rs.getString("created_by"));
            row.put("createdAt", Times.format(Db.instant(rs, "created_at")));
            return row;
        }, analysisId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("OVERLAP_ANALYSIS_NOT_FOUND",
                    "overlap analysis not found: " + analysisId);
        }
        Map<String, Object> result = rows.get(0);
        result.put("pairs", db.jdbc().queryForList(
                "select * from boundary_overlap_pair where analysis_id = ? order by id", analysisId));
        return result;
    }

    public List<Map<String, Object>> listAnalyses(Long parcelId) {
        if (parcelId == null) {
            return db.jdbc().queryForList("""
                    select a.id, a.parcel_id, p.parcel_code, a.comparison_scope, a.policy_tolerance_pct,
                           a.status, a.summary, a.created_at
                      from boundary_overlap_analysis a join parcel p on p.id = a.parcel_id
                     order by a.id desc
                    """);
        }
        return db.jdbc().queryForList("""
                select a.id, a.parcel_id, p.parcel_code, a.comparison_scope, a.policy_tolerance_pct,
                       a.status, a.summary, a.created_at
                  from boundary_overlap_analysis a join parcel p on p.id = a.parcel_id
                 where a.parcel_id = ? order by a.id desc
                """, parcelId);
    }

    // ----------------------------------------------------------------- helpers

    private BoundaryDtos.BoundaryNode node(BoundaryVersionRow row) {
        return new BoundaryDtos.BoundaryNode(row.parcelId(), row.parcelCode(), row.id(), row.versionNo(),
                row.areaHa(), row.validFrom());
    }

    public List<BoundaryVersionRow> boundaryVersions(long parcelId) {
        return db.jdbc().query("""
                        select b.id, b.parcel_id, p.parcel_code, b.version_no, b.geometry_geojson,
                               b.area_ha, b.valid_from, b.ingested_at
                          from parcel_boundary_version b join parcel p on p.id = b.parcel_id
                         where b.parcel_id = ? order by b.version_no
                        """,
                (rs, i) -> new BoundaryVersionRow(rs.getLong("id"), rs.getLong("parcel_id"),
                        rs.getString("parcel_code"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")),
                        rs.getBigDecimal("area_ha").doubleValue(),
                        rs.getObject("valid_from", LocalDate.class), Db.instant(rs, "ingested_at")),
                parcelId);
    }

    public BoundaryVersionRow boundaryVersion(long boundaryVersionId) {
        List<BoundaryVersionRow> rows = db.jdbc().query("""
                        select b.id, b.parcel_id, p.parcel_code, b.version_no, b.geometry_geojson,
                               b.area_ha, b.valid_from, b.ingested_at
                          from parcel_boundary_version b join parcel p on p.id = b.parcel_id
                         where b.id = ?
                        """,
                (rs, i) -> new BoundaryVersionRow(rs.getLong("id"), rs.getLong("parcel_id"),
                        rs.getString("parcel_code"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")),
                        rs.getBigDecimal("area_ha").doubleValue(),
                        rs.getObject("valid_from", LocalDate.class), Db.instant(rs, "ingested_at")),
                boundaryVersionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("BOUNDARY_NOT_FOUND",
                    "boundary version not found: " + boundaryVersionId);
        }
        return rows.get(0);
    }

    private List<BoundaryVersionRow> neighbourBoundaries(long parcelId) {
        List<BoundaryVersionRow> all = db.jdbc().query("""
                        select b.id, b.parcel_id, p.parcel_code, b.version_no, b.geometry_geojson,
                               b.area_ha, b.valid_from, b.ingested_at
                          from parcel_boundary_version b join parcel p on p.id = b.parcel_id
                         where b.parcel_id <> ?
                         order by b.parcel_id, b.version_no
                        """,
                (rs, i) -> new BoundaryVersionRow(rs.getLong("id"), rs.getLong("parcel_id"),
                        rs.getString("parcel_code"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")),
                        rs.getBigDecimal("area_ha").doubleValue(),
                        rs.getObject("valid_from", LocalDate.class), Db.instant(rs, "ingested_at")),
                parcelId);
        return all;
    }

    private void requireParcel(long parcelId) {
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from parcel where id = ?", Integer.class, parcelId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("PARCEL_NOT_FOUND", "parcel not found: " + parcelId);
        }
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "BOTH";
        }
        String normalized = scope.trim().toUpperCase();
        return switch (normalized) {
            case "PARCEL_VERSIONS", "NEIGHBOURS", "BOTH" -> normalized;
            default -> throw ApiException.badRequest("INVALID_SCOPE",
                    "scope must be PARCEL_VERSIONS, NEIGHBOURS or BOTH");
        };
    }

    private static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
