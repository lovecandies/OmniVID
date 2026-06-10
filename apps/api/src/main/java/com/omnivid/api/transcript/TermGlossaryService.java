package com.omnivid.api.transcript;

import com.omnivid.api.common.ApiException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TermGlossaryService {
    private final TermGlossaryRepository repository;
    private final SubtitleTextSanitizer sanitizer;

    public TermGlossaryService(TermGlossaryRepository repository, SubtitleTextSanitizer sanitizer) {
        this.repository = repository;
        this.sanitizer = sanitizer;
    }

    public List<TermGlossaryEntry> list() {
        return repository.list();
    }

    public TermGlossaryEntry create(TermGlossaryCreateRequest request) {
        String sourcePattern = normalizeInput(request.sourcePattern(), "sourcePattern");
        String replacement = normalizeInput(request.replacement(), "replacement");
        if (sourcePattern.equalsIgnoreCase(replacement)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sourcePattern and replacement must be different");
        }
        return repository.save(sourcePattern, replacement);
    }

    public TermGlossaryEntry setEnabled(long id, boolean enabled) {
        repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Term glossary entry not found"));
        repository.setEnabled(id, enabled);
        return repository.findById(id).orElseThrow();
    }

    public void delete(long id) {
        repository.delete(id);
    }

    public String apply(String text) {
        String current = sanitizer.normalizeTranscript(text);
        if (current.isBlank()) {
            return current;
        }
        for (TermGlossaryEntry entry : repository.listEnabled()) {
            current = compile(entry.sourcePattern())
                    .matcher(current)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(entry.replacement()));
        }
        return sanitizer.normalizeTranscript(current);
    }

    private Pattern compile(String sourcePattern) {
        try {
            return Pattern.compile(sourcePattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException ignored) {
            return Pattern.compile(Pattern.quote(sourcePattern), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
    }

    private String normalizeInput(String value, String field) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        if (normalized.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return normalized;
    }
}
