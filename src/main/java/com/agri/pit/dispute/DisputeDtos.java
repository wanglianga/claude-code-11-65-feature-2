package com.agri.pit.dispute;

public final class DisputeDtos {

    public record RuleRequest(String ruling, String decidedBy, String note) {
    }

    public record WithdrawRequest(String withdrawnBy, String note) {
    }

    public record ReviewRequest(String requestedBy, String reason) {
    }

    public record AllocationView(long id, long sceneId, long cellId, Long runId, Long caseId,
                                 long parcelId, long boundaryVersionId, String allocationStatus,
                                 String ruleCode, Long overlapAnalysisId, Long disputeId,
                                 double ndvi, String reason, String allocatedAt) {
    }

    public record RulingView(long id, String action, String ruling, String decidedBy, String decidedAt,
                             String note, Double retainedAreaHa, Long retainedSnapshotId,
                             Long samplingRunId) {
    }

    public record ReviewRequestView(long id, long disputeId, long caseId, Long samplingRunId, String status,
                                    String requestedBy, String requestedAt, String reason,
                                    Long priorRulingId, Double priorAreaHa, Long priorSnapshotId,
                                    String completedAt) {
    }

    public record DisputeView(long id, String disputeNo, Long caseId, String caseNo, long parcelId,
                              Long overlapAnalysisId, Long overlapPairId,
                              long subjectBoundaryVersionIdA, long subjectBoundaryVersionIdB,
                              double overlapAreaHa, double tolerancePct, String status, String reason,
                              String openedBy, String openedAt, String ruling, String ruledBy,
                              String ruledAt, String rulingNote, String withdrawnBy, String withdrawnAt,
                              String withdrawalNote, String closedAt,
                              java.util.List<RulingView> rulings,
                              java.util.List<ReviewRequestView> reviewRequests,
                              long pendingCellCount) {
    }
}
