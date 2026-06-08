package com.omnivid.api.redis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/redis")
public class RedisInspectController {
    private final RedisInspectService service;

    public RedisInspectController(RedisInspectService service) {
        this.service = service;
    }

    @GetMapping("/inspect")
    RedisInspectResponse inspect() {
        return service.inspect();
    }
}
