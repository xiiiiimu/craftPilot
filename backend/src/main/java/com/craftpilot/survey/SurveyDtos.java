package com.craftpilot.survey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SurveyDtos {
    private SurveyDtos() {}

    public record CreateSurveyRequest(@NotEmpty List<UUID> revisionIds) {}

    public record SurveyView(UUID id, UUID groupId, String status, Instant startsAt, Instant plannedEndsAt,
                             Instant intakeUntil, Instant closingDeadlineAt, Instant closingStartedAt,
                             Instant closedAt, Long evidenceWatermark, long version) {}

    public record CreatePollRequest(@NotNull UUID revisionId,
                                    @NotBlank String kind,
                                    @NotBlank @Size(max = 240) String question,
                                    @NotEmpty List<@NotBlank @Size(max = 160) String> options) {}

    public record PollView(UUID id, UUID revisionId, String kind, String question, List<OptionView> options) {}

    public record OptionView(UUID id, String label, int position) {}

    public record VoteRequest(@NotNull UUID optionId, Long expectedVersion) {}

    public record VoteView(UUID pollId, UUID optionId, long version) {}

    public record PollStatsView(UUID pollId, String kind, List<OptionStat> options, long totalVotes) {}

    public record OptionStat(UUID optionId, String label, long votes) {}
}
