package com.omnivid.api.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TranscriptContextRepairService {
    private static final List<ContextRule> RULES = List.of(
            rule("redis", "(?iu)(?<![A-Za-z])read\\s+is(?![A-Za-z])|(?<![A-Za-z])ready\\s+is(?![A-Za-z])|瑞蒂斯|雷蒂斯", "Redis"),
            rule("mysql", "(?iu)买\\s*SQL|麦\\s*SQL|(?<![A-Za-z])mine\\s+sql(?![A-Za-z])", "MySQL"),
            rule("mybatis", "(?iu)(?<![A-Za-z])my\\s+bat\\s*is(?![A-Za-z])|买\\s*batis", "MyBatis"),
            rule("docker", "(?iu)(?<![A-Za-z])dock\\s+her(?![A-Za-z])|打客|多克|道客", "Docker"),
            rule("qdrant", "(?iu)(?<![A-Za-z])q\\s*drant(?![A-Za-z])|向量库", "Qdrant"),
            rule("embedding", "(?iu)(?<![A-Za-z])im\\s*bedding(?![A-Za-z])|嵌入向量", "Embedding"),
            rule("rerank", "(?iu)(?<![A-Za-z])re\\s*rank(?![A-Za-z])|重排序", "Rerank"),
            rule("agent", "(?iu)(?<![A-Za-z])a\\s+gent(?![A-Za-z])|智能代理", "Agent"),
            rule("rag", "(?iu)检索增强生成|(?<![A-Za-z])rack(?![A-Za-z])", "RAG"),
            rule("claude", "(?iu)(?<![A-Za-z])cloud\\s+code(?![A-Za-z])|克劳德\\s*(代码|扣的|code)", "Claude Code"),
            rule("codex", "(?iu)(?<![A-Za-z])cortex(?![A-Za-z])|代码\\s*x", "Codex"),
            rule("jvm", "(?iu)(?<![A-Za-z])java\\s+virtual\\s+machine(?![A-Za-z])|虚拟机内存", "JVM"),
            rule("oom", "(?iu)内存溢出|(?<![A-Za-z])out\\s+of\\s+memory(?![A-Za-z])", "OOM"),
            rule("mq", "(?iu)消息队列|(?<![A-Za-z])message\\s+queue(?![A-Za-z])", "MQ")
    );

    private final SubtitleTextSanitizer sanitizer;

    public TranscriptContextRepairService(SubtitleTextSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public List<RepairPatch> buildRepairPlan(List<TranscriptSegment> segments) {
        String context = buildContext(segments);
        List<RepairPatch> patches = new ArrayList<>();
        for (TranscriptSegment segment : segments) {
            String original = segment.content() == null ? "" : segment.content();
            String repaired = sanitizer.normalizeTranscript(original);
            for (ContextRule rule : RULES) {
                if (hasEvidence(context, rule.term())) {
                    repaired = rule.pattern().matcher(repaired).replaceAll(rule.replacement());
                }
            }
            repaired = sanitizer.normalizeTranscript(repaired);
            if (isSafeReplacement(original, repaired)) {
                patches.add(new RepairPatch(segment.id(), repaired));
            }
        }
        return patches;
    }

    private String buildContext(List<TranscriptSegment> segments) {
        StringBuilder builder = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(sanitizer.normalizeTranscript(segment.content()).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private boolean hasEvidence(String context, String term) {
        return switch (term) {
            case "redis" -> containsAny(context, "redis", "redisson", "setnx")
                    || containsAtLeast(context, 2, "缓存", "分布式锁", "热点 key", "令牌桶");
            case "mysql" -> containsAny(context, "mysql", "sql", "数据库", "唯一索引", "事务", "回表", "explain");
            case "mybatis" -> containsAny(context, "mybatis", "mapper", "动态 sql", "批量入库");
            case "docker" -> containsAny(context, "docker", "容器", "镜像", "compose");
            case "qdrant" -> containsAny(context, "qdrant", "向量数据库", "vector", "embedding");
            case "embedding" -> containsAny(context, "embedding", "向量", "召回", "rag");
            case "rerank" -> containsAny(context, "rerank", "重排", "召回", "向量");
            case "agent" -> containsAny(context, "agent", "工具调用", "多轮", "问答", "rag");
            case "rag" -> containsAny(context, "rag", "检索增强", "向量", "召回", "引用");
            case "claude" -> containsAny(context, "claude", "claude code", "codex", "ai 编程");
            case "codex" -> containsAny(context, "codex", "claude code", "代码", "vibecoding");
            case "jvm" -> containsAny(context, "jvm", "堆内存", "gc", "线程", "java");
            case "oom" -> containsAny(context, "oom", "内存", "jvm", "堆");
            case "mq" -> containsAny(context, "mq", "消息", "rocketmq", "异步", "队列");
            default -> false;
        };
    }

    private boolean isSafeReplacement(String original, String repaired) {
        if (repaired.isBlank() || repaired.equals(original)) {
            return false;
        }
        String normalizedOriginal = sanitizer.normalizeTranscript(original);
        if (repaired.equals(normalizedOriginal) && normalizedOriginal.equals(original)) {
            return false;
        }
        int originalLength = Math.max(1, normalizedOriginal.length());
        int repairedLength = repaired.length();
        return repairedLength <= originalLength + 24 && repairedLength >= Math.max(1, originalLength / 3);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAtLeast(String text, int threshold, String... needles) {
        int count = 0;
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count >= threshold;
    }

    private static ContextRule rule(String term, String regex, String replacement) {
        return new ContextRule(term, Pattern.compile(regex), replacement);
    }

    private record ContextRule(String term, Pattern pattern, String replacement) {
    }

    public record RepairPatch(long segmentId, String text) {
    }
}
