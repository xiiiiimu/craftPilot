package com.craftpilot.runtime.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Round 3: checks the structured part of a research final answer against what the tools returned in the same run.
 * <ul>
 *   <li>{@code pollReadings} ({@code [{pollId, option, votes, total}]}) must copy the survey_get_stats numbers exactly,
 *       and must not be empty when survey_get_stats returned polls.</li>
 *   <li>Each {@code concerns} entry ({@code {text, evidenceIds, mentionedBy}}) may claim at most as many people as
 *       there are different authors behind its cited evidence.</li>
 * </ul>
 * Only these structured fields are checked; the free-text summary is not scanned. Returns human-readable problems,
 * which are also shown to the model in the corrective turn; empty means consistent.
 */
final class FinalConsistencyCheck {
    private FinalConsistencyCheck() {
    }

    /**
     * @param answer the parsed final JSON object
     * @param seenPolls poll objects from this run's survey_get_stats results ({@code pollId, totalVotes, options})
     * @param authorByEvidence author of each opinion evidence id cited in {@code concerns} (others are absent)
     */
    static List<String> check(JsonNode answer, List<JsonNode> seenPolls, Map<UUID, UUID> authorByEvidence) {
        List<String> problems = new ArrayList<>();
        checkPolls(answer.path("pollReadings"), seenPolls, problems);
        checkConcerns(answer.path("concerns"), authorByEvidence, problems);
        return problems;
    }

    private static void checkPolls(JsonNode readings, List<JsonNode> seenPolls, List<String> problems) {
        Map<String, JsonNode> polls = new LinkedHashMap<>();
        for (JsonNode poll : seenPolls) {
            polls.put(poll.path("pollId").asText(), poll);
        }
        boolean empty = !readings.isArray() || readings.isEmpty();
        if (empty) {
            if (!polls.isEmpty()) {
                problems.add("pollReadings is empty but survey_get_stats returned " + polls.size()
                        + " poll(s): copy their numbers");
            }
            return;
        }
        if (polls.isEmpty()) {
            problems.add("pollReadings lists polls but survey_get_stats returned none for this run");
            return;
        }
        for (int i = 0; i < readings.size(); i++) {
            JsonNode reading = readings.get(i);
            String pollId = reading.path("pollId").asText();
            JsonNode poll = polls.get(pollId);
            if (poll == null) {
                problems.add("pollReadings[" + i + "]: poll " + pollId + " was not returned by survey_get_stats");
                continue;
            }
            String option = reading.path("option").asText();
            JsonNode match = null;
            for (JsonNode candidate : poll.path("options")) {
                if (candidate.path("label").asText().equals(option)) {
                    match = candidate;
                }
            }
            if (match == null) {
                problems.add("pollReadings[" + i + "]: \"" + option + "\" is not an option of poll " + pollId);
                continue;
            }
            String where = "poll " + pollId + " option \"" + option + "\": ";
            long votes = match.path("votes").asLong();
            long total = poll.path("totalVotes").asLong();
            if (!sameNumber(reading.path("votes"), votes)) {
                problems.add(where + "you wrote " + reading.path("votes").asText() + " votes, survey_get_stats returned "
                        + votes);
            }
            if (!sameNumber(reading.path("total"), total)) {
                problems.add(where + "you wrote total " + reading.path("total").asText()
                        + ", survey_get_stats returned " + total);
            }
        }
    }

    private static void checkConcerns(JsonNode concerns, Map<UUID, UUID> authorByEvidence, List<String> problems) {
        if (!concerns.isArray()) {
            return;
        }
        for (int i = 0; i < concerns.size(); i++) {
            JsonNode concern = concerns.get(i);
            String label = "concerns[" + i + "] (\"" + concern.path("text").asText() + "\"): ";
            JsonNode claimed = concern.path("mentionedBy");
            if (!claimed.isIntegralNumber() || claimed.asLong() < 1) {
                problems.add(label + "mentionedBy must be a positive integer");
                continue;
            }
            Set<UUID> authors = new HashSet<>();
            for (UUID id : evidenceIds(concern.path("evidenceIds"))) {
                UUID author = authorByEvidence.get(id);
                if (author != null) {
                    authors.add(author);
                }
            }
            if (authors.size() < claimed.asLong()) {
                problems.add(label + "mentionedBy is " + claimed.asLong() + " but its evidence comes from "
                        + authors.size() + " different author(s); cite evidence from each person or lower mentionedBy");
            }
        }
    }

    /** UUIDs cited by the {@code concerns} entries of an answer (non-UUID values are skipped). */
    static List<UUID> concernEvidenceIds(JsonNode answer) {
        List<UUID> ids = new ArrayList<>();
        if (answer.path("concerns").isArray()) {
            for (JsonNode concern : answer.path("concerns")) {
                ids.addAll(evidenceIds(concern.path("evidenceIds")));
            }
        }
        return ids;
    }

    private static List<UUID> evidenceIds(JsonNode node) {
        List<UUID> ids = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                try {
                    ids.add(UUID.fromString(n.asText()));
                } catch (IllegalArgumentException ignored) {
                    // not an id: contributes no author
                }
            }
        }
        return ids;
    }

    private static boolean sameNumber(JsonNode written, long actual) {
        return written.isIntegralNumber() && written.asLong() == actual;
    }
}
