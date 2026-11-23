package com.craftpilot.runtime.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The structured part of the final answer must agree with what the tools returned (round 3). */
class FinalConsistencyCheckTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID POLL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID A1 = UUID.randomUUID();
    private static final UUID A2 = UUID.randomUUID();
    private static final UUID B1 = UUID.randomUUID();
    private static final UUID AUTHOR_A = UUID.randomUUID();
    private static final UUID AUTHOR_B = UUID.randomUUID();
    private static final Map<UUID, UUID> AUTHORS = Map.of(A1, AUTHOR_A, A2, AUTHOR_A, B1, AUTHOR_B);

    private static List<JsonNode> polls() {
        return List.of(json("""
                {"pollId":"%s","kind":"PURCHASE_INTENT","question":"开售后会买吗？","totalVotes":11,
                 "options":[{"label":"会买","votes":9},{"label":"不会买","votes":2}]}""".formatted(POLL)));
    }

    private static String reading(String option, int votes, int total) {
        return "{\"pollId\":\"%s\",\"option\":\"%s\",\"votes\":%d,\"total\":%d}".formatted(POLL, option, votes, total);
    }

    @Test
    void readingsThatMatchTheToolResultPass() {
        JsonNode answer = json("{\"pollReadings\":[" + reading("会买", 9, 11) + "," + reading("不会买", 2, 11)
                + "],\"concerns\":[],\"recommendation\":\"PRODUCE\"}");
        assertThat(FinalConsistencyCheck.check(answer, polls(), AUTHORS)).isEmpty();
    }

    @Test
    void wrongVotesOrTotalAreReportedWithTheRealNumbers() {
        JsonNode answer = json("{\"pollReadings\":[" + reading("会买", 2, 11) + "," + reading("不会买", 2, 4) + "]}");
        assertThat(FinalConsistencyCheck.check(answer, polls(), AUTHORS)).containsExactly(
                "poll " + POLL + " option \"会买\": you wrote 2 votes, survey_get_stats returned 9",
                "poll " + POLL + " option \"不会买\": you wrote total 4, survey_get_stats returned 11");
    }

    @Test
    void unknownPollOrOptionIsReported() {
        UUID other = UUID.randomUUID();
        JsonNode answer = json("{\"pollReadings\":[{\"pollId\":\"" + other + "\",\"option\":\"会买\",\"votes\":9,"
                + "\"total\":11}," + reading("还行", 0, 11) + "]}");
        assertThat(FinalConsistencyCheck.check(answer, polls(), AUTHORS)).containsExactly(
                "pollReadings[0]: poll " + other + " was not returned by survey_get_stats",
                "pollReadings[1]: \"还行\" is not an option of poll " + POLL);
    }

    @Test
    void readingsMustNotBeEmptyWhenPollsWereSeenAndMustBeEmptyWhenNoneWere() {
        assertThat(FinalConsistencyCheck.check(json("{\"recommendation\":\"PRODUCE\"}"), polls(), AUTHORS))
                .containsExactly("pollReadings is empty but survey_get_stats returned 1 poll(s): copy their numbers");
        assertThat(FinalConsistencyCheck.check(json("{\"pollReadings\":[" + reading("会买", 9, 11) + "]}"),
                List.of(), AUTHORS))
                .containsExactly("pollReadings lists polls but survey_get_stats returned none for this run");
        assertThat(FinalConsistencyCheck.check(json("{\"pollReadings\":[]}"), List.of(), AUTHORS)).isEmpty();
    }

    @Test
    void mentionedByMustNotExceedTheDistinctAuthorsOfTheCitedEvidence() {
        String sameAuthor = "{\"text\":\"掉毛\",\"evidenceIds\":[\"" + A1 + "\",\"" + A2 + "\"],\"mentionedBy\":2}";
        String twoAuthors = "{\"text\":\"掉毛\",\"evidenceIds\":[\"" + A1 + "\",\"" + B1 + "\"],\"mentionedBy\":2}";
        String single = "{\"text\":\"杯口不平\",\"evidenceIds\":[\"" + A1 + "\"],\"mentionedBy\":1}";
        String readings = "\"pollReadings\":[" + reading("会买", 9, 11) + "]";

        assertThat(FinalConsistencyCheck.check(json("{" + readings + ",\"concerns\":[" + sameAuthor + "]}"),
                polls(), AUTHORS)).containsExactly(
                "concerns[0] (\"掉毛\"): mentionedBy is 2 but its evidence comes from 1 different author(s); "
                        + "cite evidence from each person or lower mentionedBy");
        assertThat(FinalConsistencyCheck.check(
                json("{" + readings + ",\"concerns\":[" + twoAuthors + "," + single + "]}"), polls(), AUTHORS))
                .isEmpty();
    }

    @Test
    void mentionedByMustBeAPositiveInteger() {
        String readings = "\"pollReadings\":[" + reading("会买", 9, 11) + "]";
        for (String value : new String[] {"0", "\"2\"", "1.5", "null"}) {
            String concern = "{\"text\":\"x\",\"evidenceIds\":[\"" + A1 + "\"],\"mentionedBy\":" + value + "}";
            assertThat(FinalConsistencyCheck.check(json("{" + readings + ",\"concerns\":[" + concern + "]}"),
                    polls(), AUTHORS)).as(value)
                    .containsExactly("concerns[0] (\"x\"): mentionedBy must be a positive integer");
        }
        String missing = "{\"text\":\"x\",\"evidenceIds\":[\"" + A1 + "\"]}";
        assertThat(FinalConsistencyCheck.check(json("{" + readings + ",\"concerns\":[" + missing + "]}"),
                polls(), AUTHORS)).containsExactly("concerns[0] (\"x\"): mentionedBy must be a positive integer");
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
