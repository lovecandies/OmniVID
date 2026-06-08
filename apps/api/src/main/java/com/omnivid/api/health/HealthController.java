package com.omnivid.api.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "service", "omnivid-api"
        );
    }
}
