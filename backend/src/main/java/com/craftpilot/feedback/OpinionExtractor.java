package com.craftpilot.feedback;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class OpinionExtractor {
    public record ExtractedOpinion(String polarity, String attributeKey, String canonicalText,
                                   String quoteText, BigDecimal confidence) {}

    public List<ExtractedOpinion> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        List<ExtractedOpinion> opinions = new ArrayList<>();
        if (containsAny(lower, "喜欢", "不错", "好看", "love", "great", "good")) {
            opinions.add(new ExtractedOpinion("POSITIVE", "overall", "positive-overall",
                    clip(normalized), new BigDecimal("0.8200")));
        }
        if (containsAny(lower, "不喜欢", "难看", "差", "hate", "bad", "ugly")) {
            opinions.add(new ExtractedOpinion("NEGATIVE", "overall", "negative-overall",
                    clip(normalized), new BigDecimal("0.8200")));
        }
        if (containsAny(lower, "面料", "fabric", "材质")) {
            String polarity = containsAny(lower, "硬", "粗糙", "差", "bad") ? "NEGATIVE" : "NEUTRAL";
            opinions.add(new ExtractedOpinion(polarity, "fabric", "fabric-mention",
                    clip(normalized), new BigDecimal("0.7000")));
        }
        if (opinions.isEmpty()) {
            opinions.add(new ExtractedOpinion("NEUTRAL", "overall", "neutral-mention",
                    clip(normalized), new BigDecimal("0.5000")));
        }
        return List.copyOf(opinions);
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
