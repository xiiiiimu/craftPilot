package com.craftpilot.retrieval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RetrievalController {
    public record SearchRequest(@NotNull UUID revisionId, @NotBlank String query,
                                @Min(1) @Max(50) Integer topK) {}

    private final RetrievalService retrieval;

    public RetrievalController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @PostMapping("/merchants/{merchantId}/surveys/{surveyId}/retrieval/search")
    RetrievalService.SearchResult search(Principal principal, @PathVariable UUID merchantId,
                                         @PathVariable UUID surveyId,
                                         @Valid @RequestBody SearchRequest request) {
        int topK = request.topK() == null ? 10 : request.topK();
        return retrieval.search(actor(principal), merchantId, surveyId, request.revisionId(),
                request.query(), topK);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
