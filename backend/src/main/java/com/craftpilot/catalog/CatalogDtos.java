package com.craftpilot.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record CreateCampaignRequest(@NotBlank @Size(max = 160) String name,
                                        @Size(max = 2000) String description) {}

    public record CampaignView(UUID id, String name, String description, Instant createdAt) {}

    public record CreateStyleRequest(@NotBlank @Size(max = 160) String name,
                                     @NotBlank @Size(max = 80) String category) {}

    public record StyleView(UUID id, UUID campaignId, String name, String category) {}

    public record BomItemRequest(@Positive int lineNo,
                                 @NotBlank @Size(max = 80) String materialCode,
                                 @NotBlank @Size(max = 160) String materialName,
                                 @NotNull @Positive BigDecimal quantity,
                                 @NotBlank @Size(max = 20) String unit,
                                 BigDecimal unitCost,
                                 @Size(min = 3, max = 3) String currency,
                                 boolean costGap) {}

    public record CreateRevisionRequest(UUID parentRevisionId,
                                        @NotEmpty List<UUID> assetIds,
                                        @NotEmpty List<BomItemRequest> bomItems,
                                        @Size(max = 2000) String notes) {}

    public record RevisionView(UUID id, UUID styleId, long revisionNo, UUID parentRevisionId,
                               String notes, String snapshotHash, Instant createdAt,
                               List<UUID> assetIds, List<BomItemView> bomItems) {}

    public record BomItemView(int lineNo, String materialCode, String materialName,
                              BigDecimal quantity, String unit, BigDecimal unitCost,
                              String currency, boolean costGap) {}

    public record AssetView(UUID id, String kind, String contentType, String status,
                            Long byteSize, String contentSha256, Instant createdAt) {}
}
