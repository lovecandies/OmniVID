package com.omnivid.api.mysql;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mysql")
public class MysqlExplainController {
    private final MysqlExplainService service;

    public MysqlExplainController(MysqlExplainService service) {
        this.service = service;
    }

    @GetMapping("/explain")
    MysqlExplainResponse explain() {
        return service.explain();
    }
}
