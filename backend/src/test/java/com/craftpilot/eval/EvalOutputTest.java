package com.craftpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Runs in the default build: the per-run detail columns written to eval-runs.csv. */
class EvalOutputTest {

    @Test
    void citedEvidenceMapsBackToDatasetMessageKeysInCitationOrder() {
        UUID m1a = UUID.randomUUID();
        UUID m1b = UUID.randomUUID();
        UUID m3 = UUID.randomUUID();
        UUID poll = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        Map<String, List<UUID>> byMessage = new LinkedHashMap<>();
        byMessage.put("m1", List.of(m1a, m1b));
        byMessage.put("m2", List.of());
        byMessage.put("m3", List.of(m3));

        String cell = EvalOutput.citedMessages(List.of(m3, m1b, poll, m1a, unknown, new UUID(0, 0)), byMessage,
                Map.of(poll, "POLL_RESULT"));

        // m1 appears once although two of its opinions were cited; non-message evidence keeps its source type
        assertThat(cell).isEqualTo("m3;m1;POLL_RESULT;invalid;invalid");
    }

    @Test
    void noCitationsGiveAnEmptyCell() {
        assertThat(EvalOutput.citedMessages(List.of(), Map.of("m1", List.of(UUID.randomUUID())), Map.of()))
                .isEmpty();
    }

    @Test
    void summaryIsOneLineAndSurvivesCsvQuoting() throws Exception {
        String summary = EvalOutput.summaryCell("喜欢的人多,\n\"想买\"的也多\r\n  建议量产");
        assertThat(summary).isEqualTo("喜欢的人多, \"想买\"的也多 建议量产");
        assertThat(EvalOutput.summaryCell(null)).isEmpty();

        Path file = Files.createTempFile("eval-runs", ".csv");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", "C01");
        row.put("final_summary", summary);
        row.put("cited_messages", "m1;m2");
        EvalOutput.writeCsv(file, List.of(row));

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(
                "case_id,final_summary,cited_messages\nC01,\"喜欢的人多, \"\"想买\"\"的也多 建议量产\",m1;m2\n");
    }
}
