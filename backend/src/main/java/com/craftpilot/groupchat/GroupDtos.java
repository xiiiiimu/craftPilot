package com.craftpilot.groupchat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class GroupDtos {
    private GroupDtos() {}

    public record CreateGroupRequest(@NotBlank @Size(max = 120) String name) {}

    public record GroupView(UUID id, String name, long nextMessageSeq) {}

    public record UpsertMemberRequest(@NotBlank String role, boolean enabled, Long expectedVersion) {}

    public record GroupMemberView(UUID principalId, String role, boolean enabled, long version) {}

    public record MessageCard(UUID revisionId) {}

    public record PostMessageRequest(@NotNull UUID clientMsgId,
                                     @Size(max = 4000) String text,
                                     List<UUID> assetIds,
                                     UUID replyToId,
                                     MessageCard card) {}

    public record MessageView(UUID id, UUID senderId, UUID clientMsgId, long groupSeq, String text,
                              UUID replyToId, UUID cardRevisionId, UUID surveyId, Instant receivedAt) {}

    public record MessagePage(List<MessageView> items, Long nextAfterSeq) {}
}
