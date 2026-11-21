package com.agri.pit.boundary;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

public final class BoundaryDtos {

    public record AnalyzeRequest(Double tolerancePct, String scope, String createdBy) {
    }

    public record BoundaryNode(long parcelId, String parcelCode, long boundaryVersionId, int versionNo,
                               double areaHa, LocalDate validFrom) {
    }

    public record OverlapEdge(long pairId, long analysisId, BoundaryNode a, BoundaryNode b,
                              boolean sameParcel, double intersectionAreaHa,
                              double overlapPctOfA, double overlapPctOfB, boolean exceedsTolerance,
                              JsonNode intersectionGeometry, Long openDisputeId) {
    }

    public record OverlapGraph(long parcelId, String parcelCode, double tolerancePct,
                               java.util.List<BoundaryNode> nodes, java.util.List<OverlapEdge> edges) {
    }

    public record GeometryFreezeView(long id, long caseId, String caseNo, Long tenureVersionId,
                                     Integer tenureVersionNo, long boundaryVersionId, int boundaryVersionNo,
                                     String ownerName, String boundarySource, String sourceDocNo,
                                     String scoringCutoff, double areaHa, double policyTolerancePct,
                                     JsonNode geometrySnapshot, String frozenAt, String note) {
    }
}
