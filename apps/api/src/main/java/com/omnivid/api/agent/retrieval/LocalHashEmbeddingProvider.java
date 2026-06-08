package com.omnivid.api.agent.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class LocalHashEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSIONS = 256;
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_BLOCK = Pattern.compile("[\\u4e00-\\u9fa5]+");

    @Override
    public String providerName() {
        return "local-hash";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public Map<Integer, Double> embed(String text) {
        Map<Integer, Double> vector = new HashMap<>();
        for (String token : tokens(text)) {
            int index = (token.hashCode() & 0x7fffffff) % DIMENSIONS;
            vector.merge(index, 1.0, Double::sum);
        }
        return vector;
    }

    private List<String> tokens(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();

        Matcher latin = LATIN_TOKEN.matcher(normalized);
        while (latin.find()) {
            String token = latin.group();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }

        Matcher cjk = CJK_BLOCK.matcher(normalized);
        while (cjk.find()) {
            String block = cjk.group();
            if (block.length() <= 6) {
                tokens.add(block);
            }
            for (int index = 0; index < block.length() - 1; index++) {
                tokens.add(block.substring(index, index + 2));
            }
        }

        addDomainExpansions(normalized, tokens);
        return tokens;
    }

    private void addDomainExpansions(String text, List<String> tokens) {
        if (containsAny(text, "asr", "\u5b57\u5e55", "\u8bed\u97f3", "\u8f6c\u5199", "\u8bc6\u522b", "transcription", "speech")) {
            tokens.addAll(List.of("asr", "transcription", "speech", "subtitle", "audio"));
        }
        if (containsAny(text, "\u603b\u7ed3", "\u6458\u8981", "\u7eaa\u8981", "\u5927\u7eb2", "summary", "generation")) {
            tokens.addAll(List.of("summary", "generation", "outline", "minutes"));
        }
        if (containsAny(text, "\u97f3\u9891", "\u62bd\u97f3\u9891", "audio", "extraction")) {
            tokens.addAll(List.of("audio", "extraction", "ffmpeg"));
        }
        if (containsAny(text, "\u4e0a\u4f20", "\u6587\u4ef6", "upload")) {
            tokens.addAll(List.of("upload", "file", "video"));
        }
        if (containsAny(text, "\u7f13\u5b58", "redis", "\u9650\u6d41", "\u8fdb\u5ea6")) {
            tokens.addAll(List.of("redis", "cache", "rate", "progress"));
        }
        if (containsAny(text, "mysql", "\u6570\u636e\u5e93", "\u4e8b\u52a1", "\u7d22\u5f15")) {
            tokens.addAll(List.of("mysql", "database", "transaction", "index", "status"));
        }
        if (containsAny(text, "agent", "rag", "\u95ee\u7b54", "\u5f15\u7528", "\u65f6\u95f4\u6233")) {
            tokens.addAll(List.of("agent", "rag", "answer", "citation", "timestamp"));
        }
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
