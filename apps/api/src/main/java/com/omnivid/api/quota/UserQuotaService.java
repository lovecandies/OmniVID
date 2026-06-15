package com.omnivid.api.quota;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.knowledge.KnowledgeBaseRepository;
import com.omnivid.api.video.VideoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UserQuotaService {
    private final UserQuotaRepository quotas;
    private final VideoRepository videos;
    private final KnowledgeBaseRepository knowledgeBases;
    private final long defaultMaxStorageBytes;
    private final int defaultMaxVideoCount;
    private final int defaultMaxKnowledgeBaseCount;

    public UserQuotaService(
            UserQuotaRepository quotas,
            VideoRepository videos,
            KnowledgeBaseRepository knowledgeBases,
            @Value("${omnivid.account.default-max-storage-bytes:10737418240}") long defaultMaxStorageBytes,
            @Value("${omnivid.account.default-max-video-count:50}") int defaultMaxVideoCount,
            @Value("${omnivid.account.default-max-knowledge-base-count:20}") int defaultMaxKnowledgeBaseCount
    ) {
        this.quotas = quotas;
        this.videos = videos;
        this.knowledgeBases = knowledgeBases;
        this.defaultMaxStorageBytes = Math.max(1, defaultMaxStorageBytes);
        this.defaultMaxVideoCount = Math.max(1, defaultMaxVideoCount);
        this.defaultMaxKnowledgeBaseCount = Math.max(1, defaultMaxKnowledgeBaseCount);
    }

    public UserQuotaResponse current(long userId) {
        UserQuota quota = quota(userId);
        return UserQuotaResponse.of(quota, usage(userId));
    }

    public void requireCanCreateVideo(long userId, long additionalBytes) {
        UserQuota quota = quota(userId);
        UserQuotaUsage usage = usage(userId);
        if (usage.videoCount() + 1 > quota.maxVideoCount()) {
            throw quotaExceeded("Video count quota exceeded", "videos=%d/%d".formatted(usage.videoCount(), quota.maxVideoCount()));
        }
        long nextBytes = usage.storageBytes() + Math.max(0, additionalBytes);
        if (nextBytes > quota.maxStorageBytes()) {
            throw quotaExceeded("Storage quota exceeded", "storageBytes=%d/%d, incomingBytes=%d".formatted(
                    usage.storageBytes(),
                    quota.maxStorageBytes(),
                    Math.max(0, additionalBytes)
            ));
        }
    }

    public void requireCanStartUpload(long userId, long incomingBytes) {
        UserQuota quota = quota(userId);
        UserQuotaUsage usage = usage(userId);
        long nextBytes = usage.storageBytes() + Math.max(0, incomingBytes);
        if (usage.videoCount() + 1 > quota.maxVideoCount() || nextBytes > quota.maxStorageBytes()) {
            throw quotaExceeded("Upload quota exceeded", "videos=%d/%d, storageBytes=%d/%d, incomingBytes=%d".formatted(
                    usage.videoCount(),
                    quota.maxVideoCount(),
                    usage.storageBytes(),
                    quota.maxStorageBytes(),
                    Math.max(0, incomingBytes)
            ));
        }
    }

    public void requireCanCreateKnowledgeBase(long userId) {
        UserQuota quota = quota(userId);
        UserQuotaUsage usage = usage(userId);
        if (usage.knowledgeBaseCount() + 1 > quota.maxKnowledgeBaseCount()) {
            throw quotaExceeded(
                    "Knowledge base quota exceeded",
                    "knowledgeBases=%d/%d".formatted(usage.knowledgeBaseCount(), quota.maxKnowledgeBaseCount())
            );
        }
    }

    private UserQuota quota(long userId) {
        return quotas.findByUserId(userId)
                .orElseGet(() -> new UserQuota(
                        userId,
                        defaultMaxStorageBytes,
                        defaultMaxVideoCount,
                        defaultMaxKnowledgeBaseCount
                ));
    }

    private UserQuotaUsage usage(long userId) {
        return new UserQuotaUsage(
                videos.storageBytesByUserId(userId),
                videos.countByUserId(userId),
                knowledgeBases.countByUserId(userId)
        );
    }

    private ApiException quotaExceeded(String message, String detail) {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                message,
                "Upgrade quota or delete unused videos/knowledge bases before retrying.",
                detail
        );
    }
}
