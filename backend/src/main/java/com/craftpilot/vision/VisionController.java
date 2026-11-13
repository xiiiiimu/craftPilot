package com.craftpilot.vision;

import static com.craftpilot.vision.VisionDtos.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class VisionController {
    private final VisionService vision;

    public VisionController(VisionService vision) {
        this.vision = vision;
    }

    @PostMapping("/merchants/{merchantId}/vision/compare")
    VisionCompareView compare(Principal principal, @PathVariable UUID merchantId,
                              @Valid @RequestBody CompareRequest request) {
        return vision.compare(actor(principal), merchantId, request);
    }

    @GetMapping("/merchants/{merchantId}/vision/comparisons/{comparisonId}")
    VisionCompareView get(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID comparisonId) {
        return vision.get(actor(principal), merchantId, comparisonId);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
