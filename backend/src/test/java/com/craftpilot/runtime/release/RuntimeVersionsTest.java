package com.craftpilot.runtime.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeVersionsTest {

    @Test
    void defaultsToLatestPromptAndReleasedToolVersion() {
        RuntimeVersions versions = new RuntimeVersions(null, Map.of());

        assertThat(versions.researchPromptVersion()).isEmpty();
        assertThat(versions.toolVersion("survey_get_stats", "2")).isEqualTo("2");
        assertThat(versions.toolPins()).isEmpty();
    }

    @Test
    void pinsPromptAndToolVersionWithDashedToolName() {
        RuntimeVersions versions = new RuntimeVersions(" 1 ", Map.of("survey-get-stats", "1"));

        assertThat(versions.researchPromptVersion()).contains("1");
        assertThat(versions.toolVersion("survey_get_stats", "2")).isEqualTo("1");
        assertThat(versions.toolVersion("evidence_search", "1")).isEqualTo("1");
    }

    @Test
    void blankPinsMeanDefault() {
        RuntimeVersions versions = new RuntimeVersions("  ", Map.of("survey-get-stats", " "));

        assertThat(versions.researchPromptVersion()).isEmpty();
        assertThat(versions.toolPins()).isEmpty();
    }

    @Test
    void rejectsUnsupportedToolVersionOrTool() {
        assertThatThrownBy(() -> new RuntimeVersions(null, Map.of("survey-get-stats", "3")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not one of");
        assertThatThrownBy(() -> new RuntimeVersions(null, Map.of("evidence-search", "1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be pinned");
    }
}
