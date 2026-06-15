package com.omnivid.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminConsoleController {
    private final AdminConsoleService admin;

    public AdminConsoleController(AdminConsoleService admin) {
        this.admin = admin;
    }

    @GetMapping("/users")
    List<AdminUserSummary> users(@RequestParam(defaultValue = "50") int limit) {
        return admin.users(limit);
    }

    @GetMapping("/users/{userId}")
    AdminUserDetail userDetail(@PathVariable long userId) {
        return admin.userDetail(userId);
    }

    @GetMapping("/resources")
    AdminResourceUsage resources() {
        return admin.resources();
    }

    @GetMapping("/tasks")
    List<AdminTaskResponse> tasks(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return admin.tasks(status, limit);
    }

    @GetMapping("/tasks/failures")
    List<AdminTaskResponse> failures(@RequestParam(defaultValue = "50") int limit) {
        return admin.tasks("FAILED", limit);
    }

    @PostMapping("/tasks/{jobId}/mark-failed")
    AdminTaskResponse markFailed(
            @PathVariable long jobId,
            @Valid @RequestBody(required = false) AdminTaskActionRequest request
    ) {
        return admin.markFailed(jobId, request);
    }
}
