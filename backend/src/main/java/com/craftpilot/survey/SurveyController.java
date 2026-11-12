package com.craftpilot.survey;

import static com.craftpilot.survey.SurveyDtos.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SurveyController {
    private final SurveyService surveys;

    public SurveyController(SurveyService surveys) {
        this.surveys = surveys;
    }

    @PostMapping("/merchants/{merchantId}/groups/{groupId}/surveys")
    @ResponseStatus(HttpStatus.CREATED)
    SurveyView open(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID groupId,
                    @Valid @RequestBody CreateSurveyRequest request) {
        return surveys.openSurvey(actor(principal), merchantId, groupId, request);
    }

    @GetMapping("/merchants/{merchantId}/surveys/{surveyId}")
    SurveyView get(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId) {
        return surveys.getSurvey(actor(principal), merchantId, surveyId);
    }

    @PostMapping("/merchants/{merchantId}/surveys/{surveyId}/close")
    SurveyView close(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                     @RequestHeader("Idempotency-Key") UUID commandKey) {
        return surveys.closeSurvey(actor(principal), merchantId, surveyId, commandKey);
    }

    @PostMapping("/merchants/{merchantId}/surveys/{surveyId}/polls")
    @ResponseStatus(HttpStatus.CREATED)
    PollView createPoll(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                        @Valid @RequestBody CreatePollRequest request) {
        return surveys.createPoll(actor(principal), merchantId, surveyId, request);
    }

    @PutMapping("/merchants/{merchantId}/surveys/{surveyId}/polls/{pollId}/vote")
    VoteView vote(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                  @PathVariable UUID pollId, @Valid @RequestBody VoteRequest request) {
        return surveys.vote(actor(principal), merchantId, surveyId, pollId, request);
    }

    @GetMapping("/merchants/{merchantId}/surveys/{surveyId}/polls/{pollId}/stats")
    PollStatsView stats(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                        @PathVariable UUID pollId) {
        return surveys.stats(actor(principal), merchantId, surveyId, pollId);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
