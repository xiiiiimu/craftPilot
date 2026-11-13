package com.craftpilot.evidence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class EvidenceDtos {
    private EvidenceDtos() {}

    public record EvidenceView(UUID id, UUID surveyId, UUID revisionId, String sourceType, UUID sourceId,
                               String grade, String visibility, long seq, String summary, String checksum,
                               Instant capturedAt) {}

    public record EvidenceVerifyRequest(@NotEmpty List<@NotNull UUID> evidenceIds) {}

    public record EvidenceVerifyResult(List<UUID> validIds, List<UUID> invalidIds) {}
}
