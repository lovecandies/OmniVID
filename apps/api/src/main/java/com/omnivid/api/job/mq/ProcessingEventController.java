package com.omnivid.api.job.mq;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.auth.CurrentUserService;
import com.omnivid.api.video.VideoService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class ProcessingEventController {
    private final ProcessingEventRepository events;
    private final ProcessingMqStatusService statusService;
    private final VideoService videos;
    private final CurrentUserService currentUser;

    public ProcessingEventController(
            ProcessingEventRepository events,
            ProcessingMqStatusService statusService,
            VideoService videos,
            CurrentUserService currentUser
    ) {
        this.events = events;
        this.statusService = statusService;
        this.videos = videos;
        this.currentUser = currentUser;
    }

    @GetMapping("/mq/status")
    ProcessingMqStatusResponse status() {
        return statusService.status();
    }

    @GetMapping("/events")
    List<ProcessingEvent> events(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return events.list(currentUser.requireUser().id(), status, limit);
    }

    @PostMapping("/events/{eventId}/retry")
    ProcessingEvent retry(@PathVariable String eventId) {
        ProcessingEvent event = events.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing event not found"));
        videos.requireVideo(event.videoId());
        events.retry(eventId);
        return events.findById(eventId).orElseThrow();
    }
}
