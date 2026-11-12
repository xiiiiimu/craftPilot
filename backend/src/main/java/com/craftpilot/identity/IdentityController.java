package com.craftpilot.identity;

import static com.craftpilot.identity.IdentityDtos.*;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identity;

    public IdentityController(IdentityService identity) {
        this.identity = identity;
    }

    @GetMapping("/me")
    Me me(Principal principal) {
        return identity.me(actor(principal));
    }

    @GetMapping("/me/merchants")
    Page<MerchantView> merchants(Principal principal, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
                                 @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return identity.merchants(actor(principal), limit, offset);
    }

    @GetMapping("/merchants/{id}")
    MerchantView merchant(Principal principal, @PathVariable UUID id) {
        return identity.merchant(actor(principal), id);
    }

    @GetMapping("/merchants/{id}/members")
    Page<MemberView> members(Principal principal, @PathVariable UUID id,
                             @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
                             @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return identity.members(actor(principal), id, limit, offset);
    }

    @DeleteMapping("/merchants/{id}/members/{principalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Principal principal, @PathVariable UUID id, @PathVariable UUID principalId,
                @RequestHeader("Idempotency-Key") UUID commandKey) {
        identity.revokeMember(actor(principal), id, principalId, commandKey);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
