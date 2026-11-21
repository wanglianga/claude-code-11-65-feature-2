package com.agri.pit.tenure;

import java.time.Instant;
import java.time.LocalDate;

public final class TenureDtos {

    public record CreateTenureRequest(
            long parcelId,
            Integer versionNo,
            String ownerName,
            String ownerIdCode,
            String tenureType,
            long boundaryVersionId,
            String boundarySource,
            String sourceDocNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant observedAt,
            Instant ingestedAt,
            Long supersedesTenureId,
            String note) {
    }

    public record ApproveRequest(String approvedBy, String note) {
    }

    public record RejectRequest(String decidedBy, String note) {
    }

    public record TenureView(
            long id,
            long parcelId,
            String parcelCode,
            int versionNo,
            String ownerName,
            String ownerIdCode,
            String tenureType,
            long boundaryVersionId,
            Integer boundaryVersionNo,
            String boundarySource,
            String sourceDocNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            String approvedBy,
            String approvedAt,
            String observedAt,
            String ingestedAt,
            Long supersedesTenureId,
            String note) {
    }
}
