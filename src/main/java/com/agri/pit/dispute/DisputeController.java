package com.agri.pit.dispute;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Dispute desk endpoints: manual rulings, withdrawal, and review requests that create fresh
 * sampling runs, plus the read model of the pixel allocation ledger.
 */
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeDeskService desk;
    private final PixelAllocatorService allocator;

    public DisputeController(DisputeDeskService desk, PixelAllocatorService allocator) {
        this.desk = desk;
        this.allocator = allocator;
    }

    @GetMapping
    public List<DisputeDtos.DisputeView> list(@RequestParam(required = false) String status) {
        return desk.list(status);
    }

    @GetMapping("/{id}")
    public DisputeDtos.DisputeView get(@PathVariable long id) {
        return desk.getView(id);
    }

    /** Issue a manual ruling on an open dispute. The original area result is kept on the ruling row. */
    @PostMapping("/{id}/rulings")
    public DisputeDtos.DisputeView rule(@PathVariable long id, @RequestBody DisputeDtos.RuleRequest req) {
        return desk.rule(id, req);
    }

    /** Withdraw a ruling: the ruling row is retained and the dispute returns to the arbitration queue. */
    @PostMapping("/{id}/withdrawal")
    public DisputeDtos.DisputeView withdraw(@PathVariable long id,
                                            @RequestBody(required = false) DisputeDtos.WithdrawRequest req) {
        return desk.withdraw(id, req);
    }

    /**
     * Request another review. Always creates a NEW DISPUTE_RESAMPLING sampling run and records the
     * prior ruling / prior area result on the request for traceability.
     */
    @PostMapping("/{id}/review-requests")
    public DisputeDtos.ReviewRequestView requestReview(@PathVariable long id,
                                                       @RequestBody(required = false)
                                                       DisputeDtos.ReviewRequest req) {
        return desk.requestReview(id, req);
    }

    @GetMapping("/{id}/review-requests")
    public List<DisputeDtos.ReviewRequestView> reviewRequests(@PathVariable long id) {
        return desk.reviewRequests(id);
    }

    /** Pixel allocation ledger, filterable by run, allocation status or dispute. */
    @GetMapping("/pixel-allocations")
    public List<DisputeDtos.AllocationView> allocations(@RequestParam(required = false) Long runId,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Long disputeId) {
        return allocator.listAllocations(runId, status, disputeId);
    }
}
