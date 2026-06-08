package com.omnivid.api.job;

import com.omnivid.api.common.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class ProcessingJobController {
    private final ProcessingJobRepository jobs;

    public ProcessingJobController(ProcessingJobRepository jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/failures")
    List<FailedJobResponse> failures(@RequestParam(defaultValue = "8") int limit) {
        return jobs.listFailed(limit);
    }

    @GetMapping("/{jobId}")
    ProcessingJob detail(@PathVariable long jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
    }
}
