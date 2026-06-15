package com.omnivid.api.job;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.auth.CurrentUserService;
import com.omnivid.api.video.VideoService;
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
    private final VideoService videos;
    private final CurrentUserService currentUser;

    public ProcessingJobController(ProcessingJobRepository jobs, VideoService videos, CurrentUserService currentUser) {
        this.jobs = jobs;
        this.videos = videos;
        this.currentUser = currentUser;
    }

    @GetMapping("/failures")
    List<FailedJobResponse> failures(@RequestParam(defaultValue = "8") int limit) {
        return jobs.listFailed(currentUser.requireUser().id(), limit);
    }

    @GetMapping("/{jobId}")
    ProcessingJob detail(@PathVariable long jobId) {
        ProcessingJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
        videos.requireVideo(job.videoId());
        return job;
    }
}
