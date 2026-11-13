package com.craftpilot.runtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class RuntimeDtos {
    private RuntimeDtos() {}

    public record CreateTicketRequest(@NotEmpty List<@NotNull UUID> revisionIds,
                                      @NotBlank String objective,
                                      String workflowId) {}

    public record ApprovalDecisionRequest(boolean approve, String reason, long expectedVersion) {}

    public record ResumeApprovalRequest(@NotBlank String commandKey) {}

    public record MerchantDecisionRequest(@NotBlank String choice, String note, long expectedVersion) {}

    public record MemoryPublishRequest(UUID approvalId) {}

    public record ApprovalView(UUID approvalId, String status, String kind, String operationDigest,
                               long version, Instant expiresAt) {}

    public record MemoryProposalView(UUID proposalId, String status, String scopeType, String scopeKey,
                                     String summary) {}

    public record MemoryEntryView(UUID entryId, String status, long version, String summary) {}

    public record MerchantDecisionView(UUID decisionId, String status, String recommendation,
                                       String reportHash, long version) {}

    public record TicketView(UUID ticketId, UUID surveyId, String status, String objective,
                             Instant deadlineAt, long version, UUID runId, String runStatus,
                             List<UUID> revisionIds) {}

    public record CancelTicketRequest(long expectedVersion, @NotBlank String reason) {}

    public record RunView(UUID runId, UUID ticketId, UUID surveyId, String status, String currentNode,
                          long version, long checkpointHead, long leaseEpoch, Instant executionDeadline,
                          int turnsUsed, int toolCallsUsed, int tokensUsed, Object finalOutput) {}

    public record TaskView(UUID taskId, UUID runId, String logicalKey, String nodeId, String status) {}

    public record EventView(long seq, String eventType, UUID taskId, String traceId, Instant createdAt,
                            Object payload) {}

    public record ModelCallView(UUID id, String provider, String modelName, String purpose, String status,
                                String providerRequestIdRedacted, Integer inputTokens, Integer outputTokens) {}

    public record ToolCallView(UUID id, String toolName, String status, int ordinal, Object result) {}

    public record BudgetView(UUID runId, int tokenBudget, int tokensUsed, int maxTurns, int turnsUsed,
                             int maxToolCalls, int toolCallsUsed, String costBudgetUsd, String costUsedUsd) {}

    public record CancelRunRequest(long expectedVersion, @NotBlank String reason) {}
}
