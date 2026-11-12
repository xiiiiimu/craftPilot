package com.craftpilot.feedback;

import static com.craftpilot.feedback.FeedbackDtos.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class FeedbackController {
    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/merchants/{merchantId}/messages/{messageId}/attribution")
    AttributionView attribute(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID messageId) {
        return feedback.processMessage(actor(principal), merchantId, messageId);
    }

    @GetMapping("/merchants/{merchantId}/attributions/{attributionId}")
    AttributionView getAttribution(Principal principal, @PathVariable UUID merchantId,
                                   @PathVariable UUID attributionId) {
        return feedback.getAttribution(actor(principal), merchantId, attributionId);
    }

    @PostMapping("/merchants/{merchantId}/surveys/{surveyId}/feedback/{attributionId}/attribution")
    AttributionView manual(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                           @PathVariable UUID attributionId, @Valid @RequestBody ManualAttributionRequest request) {
        return feedback.manualAttribute(actor(principal), merchantId, surveyId, attributionId, request);
    }

    @GetMapping("/merchants/{merchantId}/surveys/{surveyId}/issues")
    List<IssueView> issues(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                           @RequestParam(required = false) UUID revisionId) {
        return feedback.listIssues(actor(principal), merchantId, surveyId, revisionId);
    }

    @GetMapping("/merchants/{merchantId}/surveys/{surveyId}/feedback-stats")
    FeedbackStatsView stats(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                            @RequestParam(required = false) UUID revisionId) {
        return feedback.stats(actor(principal), merchantId, surveyId, revisionId);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
