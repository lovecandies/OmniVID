package com.omnivid.api.transcript;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asr/glossary")
public class TermGlossaryController {
    private final TermGlossaryService glossary;

    public TermGlossaryController(TermGlossaryService glossary) {
        this.glossary = glossary;
    }

    @GetMapping
    List<TermGlossaryEntry> list() {
        return glossary.list();
    }

    @PostMapping
    TermGlossaryEntry create(@RequestBody TermGlossaryCreateRequest request) {
        return glossary.create(request);
    }

    @PutMapping("/{id}/enabled")
    TermGlossaryEntry setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        return glossary.setEnabled(id, enabled);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        glossary.delete(id);
    }
}
