package com.craftpilot.evidence;

import static com.craftpilot.evidence.EvidenceDtos.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class EvidenceController {
    private final EvidenceService evidence;

    public EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/merchants/{merchantId}/evidence/{evidenceId}")
    EvidenceView get(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID evidenceId) {
        return evidence.get(actor(principal), merchantId, evidenceId);
    }

    @PostMapping("/merchants/{merchantId}/surveys/{surveyId}/evidence/verify")
    EvidenceVerifyResult verify(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                                @Valid @RequestBody EvidenceVerifyRequest request) {
        return evidence.verify(actor(principal), merchantId, surveyId, request);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
