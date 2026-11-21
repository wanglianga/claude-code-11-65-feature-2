package com.agri.pit.tenure;

import com.agri.pit.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Tenure version library endpoints: register ownership / boundary-source versions, approve them,
 * and query the version chain either in full or as it was valid at a given as-of instant.
 */
@RestController
@RequestMapping("/api/tenure")
public class TenureController {

    private final TenureVersionService tenure;

    public TenureController(TenureVersionService tenure) {
        this.tenure = tenure;
    }

    @PostMapping("/versions")
    public IdResponse create(@RequestBody TenureDtos.CreateTenureRequest req) {
        return new IdResponse(tenure.createTenure(req));
    }

    @PostMapping("/versions/{id}/approve")
    public TenureDtos.TenureView approve(@PathVariable long id,
                                         @RequestBody(required = false) TenureDtos.ApproveRequest req) {
        tenure.approve(id, req == null ? null : req.approvedBy(), req == null ? null : req.note());
        return tenure.toView(tenure.requireTenure(id));
    }

    @PostMapping("/versions/{id}/reject")
    public TenureDtos.TenureView reject(@PathVariable long id,
                                        @RequestBody(required = false) TenureDtos.RejectRequest req) {
        tenure.reject(id, req == null ? null : req.decidedBy(), req == null ? null : req.note());
        return tenure.toView(tenure.requireTenure(id));
    }

    @GetMapping("/versions/{id}")
    public TenureDtos.TenureView get(@PathVariable long id) {
        return tenure.toView(tenure.requireTenure(id));
    }

    /** Full version chain of a parcel, including pending / rejected / superseded records. */
    @GetMapping("/parcels/{parcelId}/versions")
    public List<TenureDtos.TenureView> list(@PathVariable long parcelId) {
        return tenure.listForParcel(parcelId).stream().map(tenure::toView).toList();
    }

    /**
     * Point-in-time version query: the tenure that was APPROVED and visible at {@code asOf}.
     * This is exactly what case intake uses to freeze geometry; later approvals are invisible here.
     */
    @GetMapping("/parcels/{parcelId}/as-of")
    public AsOfResponse asOf(@PathVariable long parcelId, @RequestParam String at) {
        Instant instant;
        try {
            instant = Instant.parse(at);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIMESTAMP",
                    "at must be an ISO-8601 instant such as 2024-06-22T00:00:00Z");
        }
        TenureVersionService.TenureRow row = tenure.approvedTenureAsOf(parcelId, instant);
        if (row == null) {
            return new AsOfResponse(null, "NO_APPROVED_TENURE_AT_AS_OF",
                    "no approved tenure version was visible at " + at);
        }
        return new AsOfResponse(tenure.toView(row), "APPROVED_TENURE_RESOLVED",
                "resolved to tenure v" + row.versionNo());
    }

    public record IdResponse(long id) {
    }

    public record AsOfResponse(TenureDtos.TenureView tenure, String code, String message) {
    }
}
