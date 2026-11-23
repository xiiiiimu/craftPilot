package com.craftpilot.runtime.release;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Optional version pins for controlled experiments (e.g. "new prompt, old tool output").
 *
 * <ul>
 *   <li>{@code craftpilot.runtime.research-prompt-version}: research agent prompt version; empty = latest
 *       ({@code agent_spec}).</li>
 *   <li>{@code craftpilot.runtime.tool-versions.<tool>}: tool output version, tool name with dashes
 *       ({@code survey-get-stats}); empty = the released version. Only tools with more than one supported
 *       output version can be pinned.</li>
 * </ul>
 * Invalid pins fail at startup rather than silently running the default.
 */
@Component
public class RuntimeVersions {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeVersions.class);
    static final Map<String, Set<String>> PINNABLE_TOOLS = Map.of("survey_get_stats", Set.of("1", "2"));

    private final String researchPrompt;
    private final Map<String, String> tools;

    @Autowired
    public RuntimeVersions(Environment env) {
        this(Binder.get(env).bind("craftpilot.runtime.research-prompt-version", String.class).orElse(null),
                Binder.get(env).bind("craftpilot.runtime.tool-versions",
                        Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
    }

    public RuntimeVersions(String researchPrompt, Map<String, String> tools) {
        this.researchPrompt = researchPrompt == null || researchPrompt.isBlank() ? null : researchPrompt.strip();
        Map<String, String> pins = new LinkedHashMap<>();
        tools.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            String tool = key.strip().replace('-', '_');
            Set<String> supported = PINNABLE_TOOLS.get(tool);
            if (supported == null) {
                throw new IllegalStateException("craftpilot.runtime.tool-versions: " + key + " cannot be pinned");
            }
            if (!supported.contains(value.strip())) {
                throw new IllegalStateException("craftpilot.runtime.tool-versions." + key + "=" + value
                        + " is not one of " + supported);
            }
            pins.put(tool, value.strip());
        });
        this.tools = Map.copyOf(pins);
        if (this.researchPrompt != null || !this.tools.isEmpty()) {
            LOG.info("runtime_version_pins researchPrompt={} tools={}", this.researchPrompt, this.tools);
        }
    }

    public Optional<String> researchPromptVersion() {
        return Optional.ofNullable(researchPrompt);
    }

    /** Version a tool call actually runs with: the pin if set, else the released version. */
    public String toolVersion(String toolName, String releasedVersion) {
        return tools.getOrDefault(toolName, releasedVersion);
    }

    public Map<String, String> toolPins() {
        return tools;
    }
}
