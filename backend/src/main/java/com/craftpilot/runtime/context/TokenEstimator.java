package com.craftpilot.runtime.context;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Conservative prompt-size estimate used to size model reservations before the call.
 *
 * <p>Counts the serialized request (messages including tool_calls arguments, plus tool schemas): ASCII characters at
 * 4 per token, every non-ASCII character (CJK feedback text) as one token, plus a fixed overhead per message. It is
 * meant to err on the high side; the settle step corrects the ledger to the provider-reported usage.
 */
public final class TokenEstimator {
    static final int PER_MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    public static int estimate(ObjectMapper mapper, List<Map<String, Object>> messages,
                               List<Map<String, Object>> toolSchemas) {
        String serialized;
        try {
            serialized = mapper.writeValueAsString(messages)
                    + (toolSchemas == null || toolSchemas.isEmpty() ? "" : mapper.writeValueAsString(toolSchemas));
        } catch (Exception ex) {
            serialized = String.valueOf(messages) + toolSchemas;
        }
        return estimateText(serialized) + PER_MESSAGE_OVERHEAD * (messages == null ? 0 : messages.size());
    }

    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp < 0x80) {
                ascii++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        long tokens = (ascii + 3) / 4 + other;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, tokens));
    }
}
