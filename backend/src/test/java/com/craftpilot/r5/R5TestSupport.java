package com.craftpilot.r5;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Deterministic fixtures for R5 acceptance. Fixed seed makes replay reproducible.
 */
public final class R5TestSupport {
    public static final long REPLAY_SEED = 20251113L;
    public static final int TARGET_MESSAGES = 1200;

    private R5TestSupport() {}

    public record MessageSpec(UUID clientMsgId, String login, String text, boolean duplicateOfPrior,
                              String sentiment, boolean withCard) {}

    public static List<MessageSpec> buildMessageCorpus(int count, long seed, List<String> memberLogins,
                                                       UUID revisionId) {
        Random rnd = new Random(seed);
        String[] positives = {
                "喜欢面料手感", "版型好看想买", "颜色很高级", "细节到位会回购", "做工扎实支持",
                "FAB 材质舒服", "值得试产", "投票给这版", "购买意愿高", "比上一版好"
        };
        String[] negatives = {
                "袖口太紧", "线头偏多", "价格偏高", "颜色偏暗", "BOM 成本有风险",
                "肩线歪了", "面料起球", "尺寸偏小", "扣子不牢", "不建议量产"
        };
        String[] ambiguous = {
                "再看看吧", "不确定", "可能还行", "有点感觉", "说不上来",
                "和之前差不多", "还行吧", "观望", "再对比一下", "需要更多信息"
        };
        List<MessageSpec> out = new ArrayList<>(count);
        List<UUID> issued = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String login = memberLogins.get(rnd.nextInt(memberLogins.size()));
            int bucket = rnd.nextInt(100);
            String text;
            String sentiment;
            if (bucket < 40) {
                text = positives[rnd.nextInt(positives.length)] + " #" + i + " rev=" + shortId(revisionId);
                sentiment = "POS";
            } else if (bucket < 75) {
                text = negatives[rnd.nextInt(negatives.length)] + " #" + i + " rev=" + shortId(revisionId);
                sentiment = "NEG";
            } else {
                text = ambiguous[rnd.nextInt(ambiguous.length)] + " #" + i;
                sentiment = "AMB";
            }
            boolean duplicate = !issued.isEmpty() && rnd.nextInt(100) < 8;
            UUID clientMsgId;
            if (duplicate) {
                clientMsgId = issued.get(rnd.nextInt(issued.size()));
            } else {
                // deterministic UUID from seed+index
                clientMsgId = UUID.nameUUIDFromBytes(("r5-msg-" + seed + "-" + i).getBytes(StandardCharsets.UTF_8));
                issued.add(clientMsgId);
            }
            boolean withCard = rnd.nextInt(100) < 55;
            out.add(new MessageSpec(clientMsgId, login, text, duplicate, sentiment, withCard));
        }
        return out;
    }

    public static String vectorJson(float seed) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.4f", seed + (i % 7) * 0.0001f));
        }
        return sb.append(']').toString();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
