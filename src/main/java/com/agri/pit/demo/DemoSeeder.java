package com.agri.pit.demo;

import com.agri.pit.boundary.GeometryFreezeService;
import com.agri.pit.boundary.OverlapAnalysisService;
import com.agri.pit.catalog.CatalogDtos;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.Db;
import com.agri.pit.ingest.IngestDtos;
import com.agri.pit.ingest.WarehouseService;
import com.agri.pit.tenure.TenureDtos;
import com.agri.pit.tenure.TenureVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic local sample encoding the three acceptance scenarios:
 * <ol>
 *   <li><b>Late scene</b> (SCENE-2024-06-18-B): acquired before the hazard date but ingested after
 *       the case cutoff. The original case must exclude it; a controlled backfill may use it only
 *       to produce a review candidate.</li>
 *   <li><b>Overlapping boundaries</b> (parcel P-1001 v1 and v2): both cover the same ground.
 *       The case locks v1 and owns the shared pixels; a boundary-variant run on v2 must see those
 *       pixels excluded, so no raster cell is counted into two fields.</li>
 *   <li><b>Post-settlement weather revision</b> (WS-001, week of 2024-06-03): the correction is
 *       ingested weeks after the case was paid. Impact analysis creates a review candidate and
 *       leaves the frozen snapshot and payout untouched.</li>
 * </ol>
 * Seeding is idempotent: if the demo cases exist, it is skipped.
 */
@Component
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    public static final LocalDate HAZARD_DATE = LocalDate.of(2024, 6, 20);
    public static final Instant CASE_CUTOFF = Instant.parse("2024-06-22T00:00:00Z");
    public static final String SCENE_LATE = "SCENE-2024-06-18-B";
    public static final String WEATHER_REVISION_KEY = "WS-001-W2024-06-13";
    public static final String CASE_PRIMARY = "DROUGHT-2024-0001";
    public static final String CASE_SECONDARY = "DROUGHT-2024-0002";
    public static final String CASE_NEIGHBOUR = "DROUGHT-2024-0003";

    private final Db db;
    private final CatalogService catalog;
    private final WarehouseService warehouse;
    private final TenureVersionService tenure;
    private final OverlapAnalysisService overlap;
    private final GeometryFreezeService freezer;
    private final boolean enabled;

    public DemoSeeder(Db db, CatalogService catalog, WarehouseService warehouse,
                      TenureVersionService tenure, OverlapAnalysisService overlap,
                      GeometryFreezeService freezer,
                      @Value("${app.demo.seed-on-startup:true}") boolean enabled) {
        this.db = db;
        this.catalog = catalog;
        this.warehouse = warehouse;
        this.tenure = tenure;
        this.overlap = overlap;
        this.freezer = freezer;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!enabled) {
            log.info("demo seeding disabled");
            return;
        }
        Integer existing = db.jdbc().queryForObject("""
                        select count(*) from claim_case where case_no in (?, ?, ?)
                        """, Integer.class, CASE_PRIMARY, CASE_SECONDARY, CASE_NEIGHBOUR);
        if (existing != null && existing > 0) {
            log.info("demo data already present, skipping seed");
            return;
        }
        log.info("seeding drought demo scenario");
        seedCatalog();
        long[] parcels = seedParcelsAndStations();
        long parcel1 = parcels[0];
        long parcel2 = parcels[1];
        long parcel3 = parcels[2];
        seedScenes();
        seedWeather();
        seedDeclarations(parcel1, parcel2, parcel3);
        seedTenure(parcel1, parcel2, parcel3);
        seedCases(parcel1, parcel2, parcel3);
        seedOverlapAnalyses(parcel1, parcel3);
        log.info("demo seed complete");
    }

    // ------------------------------------------------------------------ catalog

    private void seedCatalog() {
        catalog.createFeature(new CatalogDtos.CreateFeatureRequest("NDVI_DROUGHT", "NDVI drought index",
                "Vegetation vigour deficit over the 30 days before the hazard date", "index",
                List.of("SATELLITE_NDVI", "PARCEL_BOUNDARY")));
        catalog.createVersion("NDVI_DROUGHT", new CatalogDtos.CreateVersionRequest(
                "clamp(1 - ndvi_mean + 0.25 * ndvi_range, 0, 1)", true, 0L, "AS_OF_CUTOFF", 30,
                "v1: vegetation deficit with dispersion penalty"));
        catalog.approve("NDVI_DROUGHT", 1, "catalog-admin", "initial drought index");

        catalog.createFeature(new CatalogDtos.CreateFeatureRequest("RAIN_ANOMALY", "Rainfall deficit",
                "Rainfall shortfall over the 60-day pre-hazard window", "mm",
                List.of("WEATHER_OBSERVATION")));
        catalog.createVersion("RAIN_ANOMALY", new CatalogDtos.CreateVersionRequest(
                "max(0, 70 - rainfall_weighted * rainfall_periods)", true, 0L, "AS_OF_CUTOFF", 60,
                "v1: rainfall shortfall vs a 70mm reference over the window"));
        catalog.approve("RAIN_ANOMALY", 1, "catalog-admin", "initial rainfall deficit");

        catalog.createFeature(new CatalogDtos.CreateFeatureRequest("CROP_EXPOSURE", "Crop exposure",
                "Declared area scaled by growth stage", "ha",
                List.of("PLANTING_DECLARATION")));
        catalog.createVersion("CROP_EXPOSURE", new CatalogDtos.CreateVersionRequest(
                "declared_area_ha * clamp(days_since_planting / 120, 0.2, 1.5)", true, 0L,
                "AS_OF_CUTOFF", 0, "v1: area scaled by growth stage"));
        catalog.approve("CROP_EXPOSURE", 1, "catalog-admin", "initial crop exposure");
    }

    // ------------------------------------------------------------- geo + assets

    private long[] seedParcelsAndStations() {
        long parcel1 = warehouse.createParcel(new IngestDtos.ParcelRequest("P-1001",
                "North ridge wheat field", "Hebei-North", "WHEAT"));
        long parcel2 = warehouse.createParcel(new IngestDtos.ParcelRequest("P-1002",
                "South valley wheat field", "Hebei-South", "WHEAT"));
        long parcel3 = warehouse.createParcel(new IngestDtos.ParcelRequest("P-1003",
                "East ridge wheat field (neighbouring farmer)", "Hebei-North", "WHEAT"));

        // Both boundary versions were available before the case cutoff, so the overlap is a data
        // reality, not a leakage problem. v1 (locked by the case) and v2 (re-survey extending east)
        // share the western cells.
        warehouse.addBoundary(parcel1, new IngestDtos.BoundaryRequest(1,
                polygon(115.900, 39.900, 115.910, 39.910),
                LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "initial survey"));
        warehouse.addBoundary(parcel1, new IngestDtos.BoundaryRequest(2,
                polygon(115.900, 39.900, 115.920, 39.910),
                LocalDate.of(2024, 6, 1), null,
                Instant.parse("2024-06-19T08:00:00Z"), Instant.parse("2024-06-21T08:00:00Z"),
                null, "re-survey before the case cutoff; overlaps v1 on the west side"));
        warehouse.addBoundary(parcel2, new IngestDtos.BoundaryRequest(1,
                polygon(115.950, 39.850, 115.960, 39.860),
                LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "initial survey"));
        // Neighbouring farmer's registered map bites roughly one third into P-1001 v1. The overlap
        // is far beyond the 5% policy tolerance, so shared pixels (e.g. cell A-03) may be attributed
        // to neither claim until the dispute desk rules.
        warehouse.addBoundary(parcel3, new IngestDtos.BoundaryRequest(1,
                polygon(115.9068, 39.900, 115.925, 39.910),
                LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "agri-department registry map; overlaps P-1001 v1 beyond tolerance"));

        long station1 = warehouse.createStation(new IngestDtos.StationRequest("WS-001",
                "North ridge station", 115.905, 39.905));
        long station2 = warehouse.createStation(new IngestDtos.StationRequest("WS-002",
                "South valley station", 115.955, 39.855));
        warehouse.linkStation(parcel1, station1, 0.7);
        warehouse.linkStation(parcel1, station2, 0.3);
        warehouse.linkStation(parcel2, station2, 1.0);
        warehouse.linkStation(parcel3, station2, 1.0);
        return new long[]{parcel1, parcel2, parcel3};
    }

    // ------------------------------------------------------------------- scenes

    private void seedScenes() {
        // Scene A: acquired 2024-06-10, ingested 2024-06-12, entirely visible at the cutoff.
        // Cells A-01..A-05 sit inside both parcel-1 boundary versions; A-08..A-09 sit only in v2.
        warehouse.ingestScene(new IngestDtos.SceneRequest("SCENE-2024-06-10-A", 1,
                Instant.parse("2024-06-10T10:30:00Z"), Instant.parse("2024-06-12T04:00:00Z"),
                8.0, footprint(115.895, 39.895, 115.965, 39.915), "sentinel-2",
                null, List.of(
                        cell("A-01", 115.9050, 39.9050, 0.42),
                        cell("A-02", 115.9060, 39.9050, 0.38),
                        cell("A-03", 115.9070, 39.9050, 0.45),
                        cell("A-04", 115.9055, 39.9060, 0.40),
                        cell("A-05", 115.9065, 39.9060, 0.36),
                        cell("A-08", 115.9130, 39.9050, 0.30),
                        cell("A-09", 115.9140, 39.9060, 0.28),
                        cell("A-10", 115.9220, 39.9050, 0.33),
                        cell("A-11", 115.9230, 39.9060, 0.31),
                        cell("A-06", 115.9550, 39.8550, 0.62),
                        cell("A-07", 115.9560, 39.8560, 0.58))));

        // Scene B: the leakage trap. Acquired 2024-06-18 (in the pre-hazard window), ingested
        // 2024-06-25 (after the 2024-06-22 cutoff). Clearer and much drier, so using it would
        // silently raise the payout of an already-settled case.
        warehouse.ingestScene(new IngestDtos.SceneRequest(SCENE_LATE, 1,
                Instant.parse("2024-06-18T09:15:00Z"), Instant.parse("2024-06-25T03:00:00Z"),
                3.0, footprint(115.895, 39.895, 115.965, 39.915), "sentinel-2",
                null, List.of(
                        cell("B-01", 115.9050, 39.9050, 0.21),
                        cell("B-02", 115.9060, 39.9050, 0.19),
                        cell("B-03", 115.9070, 39.9050, 0.24),
                        cell("B-04", 115.9055, 39.9060, 0.22),
                        cell("B-05", 115.9065, 39.9060, 0.18),
                        cell("B-06", 115.9550, 39.8550, 0.54),
                        cell("B-07", 115.9560, 39.8560, 0.51))));
    }

    // ------------------------------------------------------------------ weather

    private void seedWeather() {
        LocalDate start = HAZARD_DATE.minusDays(56);
        String[] stations = {"WS-001", "WS-002"};
        double[][] rainfall = {
                {8.0, 6.0, 4.0, 2.0, 3.0, 1.0, 0.5, 1.0},
                {12.0, 9.0, 7.0, 5.0, 4.0, 2.0, 1.0, 1.5}};
        for (int s = 0; s < stations.length; s++) {
            for (int week = 0; week < 8; week++) {
                LocalDate periodStart = start.plusWeeks(week);
                LocalDate periodEnd = periodStart.plusDays(6);
                Instant observed = endOf(periodEnd);
                Instant ingested = observed.plusSeconds(6 * 3600);
                warehouse.ingestWeather(new IngestDtos.WeatherRequest(stations[s],
                        stations[s] + "-W" + periodStart, startOf(periodStart), endOf(periodEnd),
                        rainfall[s][week], 28.0 - week * 0.3, observed, ingested, null, null,
                        "ground-station"));
            }
        }
        // The correction that arrives after settlement (ingested 2024-07-08).
        long station1 = db.jdbc().queryForObject(
                "select id from weather_station where station_code = 'WS-001'", Long.class);
        long original = db.jdbc().queryForObject("""
                        select id from weather_observation
                         where station_id = ? and external_key = ?
                        """, Long.class, station1, WEATHER_REVISION_KEY);
        warehouse.ingestWeather(new IngestDtos.WeatherRequest("WS-001", WEATHER_REVISION_KEY,
                startOf(HAZARD_DATE.minusDays(7)), endOf(HAZARD_DATE.minusDays(1)),
                14.5, 27.1, Instant.parse("2024-06-19T23:59:59Z"),
                Instant.parse("2024-07-08T02:00:00Z"), 2, original, "post-audit-correction"));
    }

    // ------------------------------------------------------------- declarations

    private void seedDeclarations(long parcel1, long parcel2, long parcel3) {
        warehouse.ingestDeclaration(new IngestDtos.DeclarationRequest("DECL-2024-1001", parcel1,
                "WHEAT", LocalDate.of(2024, 3, 15), 5.6, Instant.parse("2024-03-20T08:00:00Z"),
                Instant.parse("2024-03-22T08:00:00Z"), 1, null, "county-submission"));
        warehouse.ingestDeclaration(new IngestDtos.DeclarationRequest("DECL-2024-1002", parcel2,
                "WHEAT", LocalDate.of(2024, 3, 18), 4.9, Instant.parse("2024-03-20T08:00:00Z"),
                Instant.parse("2024-03-22T08:00:00Z"), 1, null, "county-submission"));
        warehouse.ingestDeclaration(new IngestDtos.DeclarationRequest("DECL-2024-1003", parcel3,
                "WHEAT", LocalDate.of(2024, 3, 16), 6.2, Instant.parse("2024-03-20T08:00:00Z"),
                Instant.parse("2024-03-22T08:00:00Z"), 1, null, "county-submission"));
    }

    // ---------------------------------------------------------------- tenure

    private void seedTenure(long parcel1, long parcel2, long parcel3) {
        long p1v1 = boundaryId(parcel1, 1);
        long p1v2 = boundaryId(parcel1, 2);
        long p2v1 = boundaryId(parcel2, 1);
        long p3v1 = boundaryId(parcel3, 1);

        // Parcel 1: original operator approved well before the case cutoff. The intake as-of query
        // therefore resolves to this tenure and its boundary v1.
        long t1 = tenure.createTenure(new TenureDtos.CreateTenureRequest(parcel1, 1,
                "Zhang Shoutian", "ID-HEB-1001", "SELF_OPERATED", p1v1, "AGRI_REGISTRY",
                "REG-2024-0001", LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "original registered operator"));
        tenure.approve(t1, "registry-admin", "approved at spring registration",
                Instant.parse("2024-04-03T08:00:00Z"));

        // Parcel 1: post-sowing transfer + re-survey. Approved AFTER the case cutoff, so it can only
        // trigger a follow-up review; the accepted case keeps scoring on tenure v1 / boundary v1.
        long t2 = tenure.createTenure(new TenureDtos.CreateTenureRequest(parcel1, 2,
                "Li Suifeng", "ID-HEB-2002", "TRANSFERRED", p1v2, "TRANSFER_DEED",
                "DEED-2024-0077", LocalDate.of(2024, 6, 1), null,
                Instant.parse("2024-06-19T08:00:00Z"), Instant.parse("2024-06-21T08:00:00Z"),
                t1, "land transfer recorded with the re-surveyed boundary"));
        tenure.approve(t2, "registry-admin", "transfer approved after claim intake",
                Instant.parse("2024-06-25T08:00:00Z"));

        long t3 = tenure.createTenure(new TenureDtos.CreateTenureRequest(parcel2, 1,
                "Wang Maixiang", "ID-HEB-1003", "SELF_OPERATED", p2v1, "AGRI_REGISTRY",
                "REG-2024-0002", LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "original registered operator"));
        tenure.approve(t3, "registry-admin", "approved at spring registration",
                Instant.parse("2024-04-03T08:00:00Z"));

        long t4 = tenure.createTenure(new TenureDtos.CreateTenureRequest(parcel3, 1,
                "Zhao Lintian", "ID-HEB-1004", "CONTRACTED", p3v1, "AGRI_REGISTRY",
                "REG-2024-0003", LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "neighbouring farmer; registry map overlaps P-1001"));
        tenure.approve(t4, "registry-admin", "approved at spring registration",
                Instant.parse("2024-04-03T08:00:00Z"));

        // A later re-survey that is still pending when the demo starts: it must stay invisible to
        // every accepted case and to the as-of tenure query.
        long p1v3 = warehouse.addBoundary(parcel1, new IngestDtos.BoundaryRequest(3,
                polygon(115.900, 39.900, 115.922, 39.912),
                LocalDate.of(2024, 7, 1), null,
                Instant.parse("2024-07-10T08:00:00Z"), Instant.parse("2024-07-11T08:00:00Z"),
                null, "post-hazard re-survey; pending tenure approval"));
        tenure.createTenure(new TenureDtos.CreateTenureRequest(parcel1, 3,
                "Li Suifeng", "ID-HEB-2002", "TRANSFERRED", p1v3, "RE_SURVEY",
                "SURVEY-2024-0099", LocalDate.of(2024, 7, 1), null,
                Instant.parse("2024-07-10T08:00:00Z"), Instant.parse("2024-07-11T08:00:00Z"),
                t2, "pending re-survey after the hazard; not approved, never used by historical cases"));
    }

    // -------------------------------------------------------------------- cases

    private void seedCases(long parcel1, long parcel2, long parcel3) {
        long parcel1V1 = boundaryId(parcel1, 1);
        long parcel2V1 = boundaryId(parcel2, 1);
        long parcel3V1 = boundaryId(parcel3, 1);
        insertCase(CASE_PRIMARY, "POL-2024-0001", parcel1, parcel1V1);
        insertCase(CASE_SECONDARY, "POL-2024-0002", parcel2, parcel2V1);
        insertCase(CASE_NEIGHBOUR, "POL-2024-0003", parcel3, parcel3V1);
        // Reflect that these claims were already settled before the late revision arrived. The
        // queue worker still needs the run snapshot, so settlement is applied by the demo
        // bootstrap once scoring completes (see DemoController bootstrap note / README).
    }

    private void seedOverlapAnalyses(long parcel1, long parcel3) {
        // Persist the overlap graph up front so the boundary endpoints have data immediately.
        overlap.analyzeParcel(parcel1, OverlapAnalysisService.DEFAULT_TOLERANCE_PCT, "BOTH", "demo-seeder");
        overlap.analyzeParcel(parcel3, OverlapAnalysisService.DEFAULT_TOLERANCE_PCT, "BOTH", "demo-seeder");
    }

    private long boundaryId(long parcelId, int versionNo) {
        return db.jdbc().queryForObject("""
                        select id from parcel_boundary_version where parcel_id = ? and version_no = ?
                        """, Long.class, parcelId, versionNo);
    }

    private void insertCase(String caseNo, String policyNo, long parcelId, long boundaryVersionId) {
        long caseId = db.insert("""
                        insert into claim_case
                            (case_no, policy_no, parcel_id, crop_code, hazard_type, hazard_date,
                             scoring_cutoff, boundary_version_id, status)
                        values (?, ?, ?, 'WHEAT', 'DROUGHT', ?, ?, ?, 'QUEUED')
                        """,
                caseNo, policyNo, parcelId, HAZARD_DATE, Db.timestamp(CASE_CUTOFF), boundaryVersionId);
        List<Long> versionIds = db.jdbc().query(
                "select current_version_id from feature_definition where status = 'APPROVED'",
                (rs, i) -> rs.getLong(1));
        for (Long versionId : versionIds) {
            db.jdbc().update("""
                            insert into claim_case_feature_lock (case_id, feature_definition_version_id)
                            values (?, ?)
                            """, caseId, versionId);
        }
        // Freeze the geometry before any queue worker can score the case.
        freezer.freezeForCase(new GeometryFreezeService.CaseRef(caseId, caseNo, parcelId,
                boundaryVersionId, CASE_CUTOFF), null, "demo seed intake freeze");
        db.insert("""
                        insert into feature_run
                            (case_id, mode, trigger_type, status, verdict, data_policy, cutoff_at,
                             availability_ceiling, hazard_date, boundary_version_id, boundary_variant,
                             reason, requested_by)
                        values (?, 'ORIGINAL', 'CASE_SCORING', 'QUEUED', 'NOT_EVALUATED', 'AS_OF_CUTOFF',
                                ?, ?, ?, ?, false, 'demo seed intake', 'demo-seeder')
                        """,
                caseId, Db.timestamp(CASE_CUTOFF), Db.timestamp(CASE_CUTOFF), HAZARD_DATE,
                boundaryVersionId);
    }

    // ---------------------------------------------------------------- utilities

    private static Instant startOf(LocalDate date) {
        return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    private static Instant endOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusSeconds(1);
    }

    private static IngestDtos.SceneCellRequest cell(String key, double lon, double lat, double ndvi) {
        return new IngestDtos.SceneCellRequest(key, lon, lat, ndvi, 0.09);
    }

    private JsonNode polygon(double minLon, double minLat, double maxLon, double maxLat) {
        ObjectNode node = db.mapper().createObjectNode();
        node.put("type", "Polygon");
        ArrayNode coords = node.putArray("coordinates");
        ArrayNode outer = coords.addArray();
        outer.addArray().add(minLon).add(minLat);
        outer.addArray().add(maxLon).add(minLat);
        outer.addArray().add(maxLon).add(maxLat);
        outer.addArray().add(minLon).add(maxLat);
        outer.addArray().add(minLon).add(minLat);
        return node;
    }

    private JsonNode footprint(double minLon, double minLat, double maxLon, double maxLat) {
        return polygon(minLon, minLat, maxLon, maxLat);
    }
}
