package com.craftpilot.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IdentityDtos {
    private IdentityDtos() {}

    public enum Role { OWNER, MEMBER }

    public record Me(UUID id, String login, String displayName) {}

    public record MerchantView(UUID id, String name, Role role) {}

    public record MemberView(UUID principalId, String displayName, Role role, Instant revokedAt) {}

    public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {
        static <T> Page<T> from(List<T> results, int limit, int offset) {
            return new Page<>(List.copyOf(results.subList(0, Math.min(limit, results.size()))),
                    limit, offset, results.size() > limit);
        }
    }
}
