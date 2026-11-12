package com.craftpilot.vision;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public final class VisionDtos {
    private VisionDtos() {}

    public record CompareRequest(@NotNull UUID leftRevisionId, @NotNull UUID rightRevisionId, UUID surveyId) {}

    public record VisibleDifference(String aspect, String observation, String certainty) {}

    public record VisionCompareView(UUID id, UUID leftRevisionId, UUID rightRevisionId,
                                    UUID leftAssetId, UUID rightAssetId, String modelName, String status,
                                    List<VisibleDifference> visibleDifferences, String uncertainty,
                                    UUID evidenceId, String gapCode) {}
}
