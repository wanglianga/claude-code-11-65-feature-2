package com.agri.pit.boundary;

import com.agri.pit.cases.CaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Boundary governance endpoints: overlap analysis, the overlap graph and the immutable geometry
 * freeze captured at case intake.
 */
@RestController
@RequestMapping("/api/boundary")
public class BoundaryController {

    private final OverlapAnalysisService overlap;
    private final GeometryFreezeService freezer;
    private final CaseService cases;

    public BoundaryController(OverlapAnalysisService overlap, GeometryFreezeService freezer,
                              CaseService cases) {
        this.overlap = overlap;
        this.freezer = freezer;
        this.cases = cases;
    }

    /** Compute the overlap graph for one parcel (its own version chain plus neighbouring parcels). */
    @PostMapping("/parcels/{parcelId}/overlap-analyses")
    public Map<String, Object> analyze(@PathVariable long parcelId,
                                       @RequestBody(required = false) BoundaryDtos.AnalyzeRequest req) {
        Double tolerance = req == null ? null : req.tolerancePct();
        String scope = req == null ? null : req.scope();
        String createdBy = req == null ? null : req.createdBy();
        long analysisId = overlap.analyzeParcel(parcelId, tolerance, scope, createdBy);
        return Map.of("analysisId", analysisId, "status", "COMPLETED",
                "graph", overlap.graphForParcel(parcelId, tolerance));
    }

    @GetMapping("/overlap-analyses")
    public List<Map<String, Object>> listAnalyses(@RequestParam(required = false) Long parcelId) {
        return overlap.listAnalyses(parcelId);
    }

    @GetMapping("/overlap-analyses/{id}")
    public Map<String, Object> getAnalysis(@PathVariable long id) {
        return overlap.getAnalysis(id);
    }

    /** Latest overlap graph for a parcel: nodes = boundary versions, edges = measured overlaps. */
    @GetMapping("/parcels/{parcelId}/overlap-graph")
    public BoundaryDtos.OverlapGraph graph(@PathVariable long parcelId,
                                           @RequestParam(required = false) Double tolerancePct) {
        return overlap.graphForParcel(parcelId, tolerancePct);
    }

    /** Read the geometry freeze of a case by case number. It is the exact geometry used in scoring. */
    @GetMapping("/cases/{caseNo}/geometry-freeze")
    public BoundaryDtos.GeometryFreezeView freezeByCase(@PathVariable String caseNo) {
        CaseService.CaseRow row = cases.requireCase(caseNo);
        return freezer.toView(freezer.requireByCaseId(row.id()));
    }

    @GetMapping("/geometry-freezes/{freezeId}")
    public BoundaryDtos.GeometryFreezeView freezeById(@PathVariable long freezeId) {
        List<GeometryFreezeService.FreezeRow> rows = freezer.findByFreezeId(freezeId);
        if (rows.isEmpty()) {
            throw com.agri.pit.common.ApiException.notFound("GEOMETRY_FREEZE_NOT_FOUND",
                    "geometry freeze not found: " + freezeId);
        }
        return freezer.toView(rows.get(0));
    }
}
