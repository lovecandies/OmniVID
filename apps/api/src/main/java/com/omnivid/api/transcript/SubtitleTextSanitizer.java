package com.omnivid.api.transcript;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SubtitleTextSanitizer {
    private static final List<TermRule> TECH_TERM_RULES = List.of(
            rule("(?iu)(?<![A-Za-z])my\\s+sql(?![A-Za-z])", "MySQL"),
            rule("(?iu)(?<![A-Za-z])my\\s+sequel(?![A-Za-z])", "MySQL"),
            rule("(?iu)(?<![A-Za-z])my\\s+batis(?![A-Za-z])", "MyBatis"),
            rule("(?iu)(?<![A-Za-z])my\\s+battis(?![A-Za-z])", "MyBatis"),
            rule("(?iu)(?<![A-Za-z])red\\s+is(?![A-Za-z])", "Redis"),
            rule("(?iu)(?<![A-Za-z])ray\\s+dis(?![A-Za-z])", "Redis"),
            rule("(?iu)瑞迪斯|雷迪斯", "Redis"),
            rule("(?iu)(?<![A-Za-z])ready\\s+son(?![A-Za-z])", "Redisson"),
            rule("(?iu)(?<![A-Za-z])red\\s+is\\s+son(?![A-Za-z])", "Redisson"),
            rule("(?iu)(?<![A-Za-z])set\\s*n\\s*x(?![A-Za-z])", "SETNX"),
            rule("(?iu)(?<![A-Za-z])deep\\s+seek(?![A-Za-z])", "DeepSeek"),
            rule("(?iu)(?<![A-Za-z])deep\\s+sick(?![A-Za-z])", "DeepSeek"),
            rule("(?iu)(?<![A-Za-z])q\\s*wen(?![A-Za-z])", "Qwen"),
            rule("(?iu)(?<![A-Za-z])q\\s*drant(?![A-Za-z])", "Qdrant"),
            rule("(?iu)(?<![A-Za-z])queue\\s*drant(?![A-Za-z])", "Qdrant"),
            rule("(?iu)(?<![A-Za-z])chat\\s*g\\s*p\\s*t(?![A-Za-z])", "ChatGPT"),
            rule("(?iu)(?<![A-Za-z])chat\\s*gbt(?![A-Za-z])", "ChatGPT"),
            rule("(?iu)(?<![A-Za-z])claude\\s*code(?![A-Za-z])", "Claude Code"),
            rule("(?iu)(?<![A-Za-z])cloud\\s*code(?![A-Za-z])", "Claude Code"),
            rule("(?iu)克劳德\\s*(code|代码|扣的)", "Claude Code"),
            rule("(?iu)(?<![A-Za-z])code\\s*ex(?![A-Za-z])", "Codex"),
            rule("(?iu)(?<![A-Za-z])code\\s*x(?![A-Za-z])", "Codex"),
            rule("(?iu)(?<![A-Za-z])dock\\s*er(?![A-Za-z])", "Docker"),
            rule("(?iu)(?<![A-Za-z])spring\\s+boot(s)?(?![A-Za-z])", "Spring Boot"),
            rule("(?iu)(?<![A-Za-z])spring\\s+cloud(s)?(?![A-Za-z])", "Spring Cloud"),
            rule("(?iu)(?<![A-Za-z])rocket\\s*m\\s*q(?![A-Za-z])", "RocketMQ"),
            rule("(?iu)(?<![A-Za-z])rocket\\s+queue(?![A-Za-z])", "RocketMQ"),
            rule("(?iu)(?<![A-Za-z])rabbit\\s*m\\s*q(?![A-Za-z])", "RabbitMQ"),
            rule("(?iu)(?<![A-Za-z])message\\s+queue(?![A-Za-z])", "MQ"),
            rule("(?iu)(?<![A-Za-z])im\\s*bedding(?![A-Za-z])", "Embedding"),
            rule("(?iu)(?<![A-Za-z])embed\\s+ding(?![A-Za-z])", "Embedding"),
            rule("(?iu)(?<![A-Za-z])re\\s*rank(?![A-Za-z])", "Rerank"),
            rule("(?iu)(?<![A-Za-z])vector\\s+database(?![A-Za-z])", "Vector Database"),
            rule("(?iu)(?<![A-Za-z])a\\s*[.-]?\\s*p\\s*[.-]?\\s*i(?![A-Za-z])", "API"),
            rule("(?iu)(?<![A-Za-z])a\\s*[.-]?\\s*s\\s*[.-]?\\s*r(?![A-Za-z])", "ASR"),
            rule("(?iu)(?<![A-Za-z])o\\s*[.-]?\\s*c\\s*[.-]?\\s*r(?![A-Za-z])", "OCR"),
            rule("(?iu)(?<![A-Za-z])r\\s*[.-]?\\s*a\\s*[.-]?\\s*g(?![A-Za-z])", "RAG"),
            rule("(?iu)(?<![A-Za-z])l\\s*[.-]?\\s*l\\s*[.-]?\\s*m(?![A-Za-z])", "LLM"),
            rule("(?iu)(?<![A-Za-z])a\\s*i\\s*agent(?![A-Za-z])", "AI Agent"),
            rule("(?iu)(?<![A-Za-z])aigent(?![A-Za-z])", "AI Agent"),
            rule("(?iu)(?<![A-Za-z])ai[-\\s]*zen(?![A-Za-z])", "AI Agent"),
            rule("(?iu)(?<![A-Za-z])j\\s*[.-]?\\s*v\\s*[.-]?\\s*m(?![A-Za-z])", "JVM"),
            rule("(?iu)(?<![A-Za-z])j\\s*[.-]?\\s*d\\s*[.-]?\\s*k(?![A-Za-z])", "JDK"),
            rule("(?iu)(?<![A-Za-z])s\\s*[.-]?\\s*q\\s*[.-]?\\s*l(?![A-Za-z])", "SQL"),
            rule("(?iu)(?<![A-Za-z])g\\s*[.-]?\\s*c(?![A-Za-z])", "GC"),
            rule("(?iu)(?<![A-Za-z])o\\s*[.-]?\\s*o\\s*[.-]?\\s*m(?![A-Za-z])", "OOM"),
            rule("(?iu)(?<![A-Za-z])c\\s*[.-]?\\s*a\\s*[.-]?\\s*s(?![A-Za-z])", "CAS"),
            rule("(?iu)(?<![A-Za-z])a\\s*[.-]?\\s*q\\s*[.-]?\\s*s(?![A-Za-z])", "AQS")
    );

    public String normalize(String text) {
        String normalized = text == null ? "" : text
                .replace('\uFEFF', ' ')
                .replace('\uFFFD', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (looksGarbled(normalized)) {
            return "";
        }
        return toSimplified(normalized);
    }

    public String normalizeTranscript(String text) {
        return correctTechnicalTerms(normalize(text));
    }

    public QualityReport inspect(String text) {
        String value = text == null ? "" : text;
        int replacementCount = 0;
        int controlCount = 0;
        int suspiciousLatinCount = 0;
        int traditionalCount = 0;
        int cjkCount = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0xFFFD) {
                replacementCount++;
            }
            if (Character.isISOControl(codePoint) && codePoint != '\r' && codePoint != '\n' && codePoint != '\t') {
                controlCount++;
            }
            if (isSuspiciousLatin(codePoint)) {
                suspiciousLatinCount++;
            }
            if (isCjk(codePoint)) {
                cjkCount++;
                if (isTraditionalOnly(codePoint)) {
                    traditionalCount++;
                }
            }
            offset += Character.charCount(codePoint);
        }
        boolean garbledRisk = replacementCount > 0
                || controlCount > 0
                || (value.length() >= 12 && suspiciousLatinCount > cjkCount * 2 && suspiciousLatinCount > 6);
        return new QualityReport(garbledRisk, replacementCount, controlCount, suspiciousLatinCount, traditionalCount, cjkCount);
    }

    public RepairResult repairIfBetter(String text) {
        String original = text == null ? "" : text;
        String normalized = normalize(original);
        String repaired = decodeLatin1Utf8(original);
        repaired = normalize(repaired);

        if (!repaired.isBlank() && isBetter(repaired, normalized)) {
            return new RepairResult(repaired, !repaired.equals(original));
        }
        if (!normalized.equals(original)) {
            return new RepairResult(normalized, true);
        }
        return new RepairResult(original, false);
    }

    public RepairResult repairTranscriptIfBetter(String text) {
        String original = text == null ? "" : text;
        RepairResult repaired = repairIfBetter(original);
        String corrected = correctTechnicalTerms(repaired.text());
        if (!corrected.equals(original)) {
            return new RepairResult(corrected, true);
        }
        return repaired;
    }

    private String correctTechnicalTerms(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String corrected = text;
        for (TermRule rule : TECH_TERM_RULES) {
            corrected = rule.pattern().matcher(corrected).replaceAll(rule.replacement());
        }
        return corrected.replaceAll("\\s+", " ").trim();
    }

    private String decodeLatin1Utf8(String text) {
        try {
            return new String(text.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            return text;
        }
    }

    private String toSimplified(String text) {
        if (text.isBlank() || !ZhConverterUtil.containsChinese(text)) {
            return text;
        }
        return ZhConverterUtil.toSimple(text)
                .replace('妳', '你')
                .replace('祢', '你');
    }

    private boolean isBetter(String candidate, String current) {
        QualityReport candidateQuality = inspect(candidate);
        QualityReport currentQuality = inspect(current);
        int candidateSuspicious = candidateQuality.replacementCount()
                + candidateQuality.controlCount()
                + candidateQuality.suspiciousLatinCount();
        int currentSuspicious = currentQuality.replacementCount()
                + currentQuality.controlCount()
                + currentQuality.suspiciousLatinCount();
        return candidateQuality.cjkCount() > currentQuality.cjkCount()
                || candidateSuspicious < currentSuspicious;
    }

    private boolean looksGarbled(String text) {
        QualityReport report = inspect(text);
        return text.length() >= 8 && (report.replacementCount() + report.controlCount()) * 3 > text.length();
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private boolean isSuspiciousLatin(int codePoint) {
        return codePoint == 'Ã'
                || codePoint == 'Â'
                || codePoint == 'å'
                || codePoint == 'æ'
                || codePoint == 'ç'
                || codePoint == 'è'
                || codePoint == 'é'
                || codePoint == 'ð';
    }

    private boolean isTraditionalOnly(int codePoint) {
        String text = new String(Character.toChars(codePoint));
        return !text.equals(ZhConverterUtil.toSimple(text));
    }

    public record RepairResult(String text, boolean changed) {
    }

    public record QualityReport(
            boolean garbledRisk,
            int replacementCount,
            int controlCount,
            int suspiciousLatinCount,
            int traditionalCount,
            int cjkCount
    ) {
    }

    private static TermRule rule(String regex, String replacement) {
        return new TermRule(Pattern.compile(regex), replacement);
    }

    private record TermRule(Pattern pattern, String replacement) {
    }
}
