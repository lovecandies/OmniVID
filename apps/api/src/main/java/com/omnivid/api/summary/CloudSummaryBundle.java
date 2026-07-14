package com.omnivid.api.summary;

import java.util.List;

public record CloudSummaryBundle(
        List<String> corePoints,
        List<String> meetingMinutes,
        List<String> blogOutline,
        List<String> pptOutline,
        List<String> engineeringInsights,
        String modelName
) {
}
