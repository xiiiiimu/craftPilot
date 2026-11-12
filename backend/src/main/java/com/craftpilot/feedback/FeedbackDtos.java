package com.craftpilot.feedback;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public final class FeedbackDtos {
    private FeedbackDtos() {}

    public record AttributionView(UUID id, UUID messageId, UUID surveyId, UUID revisionId, String status,
                                  String reasonCode, String sourceQuote, long version) {}

    public record ManualAttributionRequest(@NotNull UUID revisionId, Long expectedVersion) {}

    public record OpinionView(UUID id, UUID messageId, UUID revisionId, UUID authorId, String polarity,
                              String attributeKey, String canonicalText, String quoteText,
                              BigDecimal confidence) {}

    public record IssueView(UUID id, UUID revisionId, String issueType, String polarity, String title,
                            int independentAuthors, String status, List<OpinionView> opinions) {}

    public record FeedbackStatsView(UUID surveyId, UUID revisionId, long attributedMessages,
                                    long opinionCount, long issueCount, long independentAuthors) {}
}
