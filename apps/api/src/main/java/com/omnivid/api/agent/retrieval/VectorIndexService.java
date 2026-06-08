package com.omnivid.api.agent.retrieval;

import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.video.VideoAsset;
import com.omnivid.api.video.VideoService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class VectorIndexService {
    private final VideoService videos;
    private final TranscriptRepository transcripts;
    private final TranscriptVectorSearch vectorSearch;
    private final ObjectProvider<QdrantVectorStore> qdrant;

    public VectorIndexService(
            VideoService videos,
            TranscriptRepository transcripts,
            TranscriptVectorSearch vectorSearch,
            ObjectProvider<QdrantVectorStore> qdrant
    ) {
        this.videos = videos;
        this.transcripts = transcripts;
        this.vectorSearch = vectorSearch;
        this.qdrant = qdrant;
    }

    public VectorIndexRebuildResponse rebuildDefaultKnowledgeBase() {
        List<VideoAsset> videoAssets = videos.listVideos();
        List<Long> videoIds = videoAssets.stream().map(VideoAsset::id).toList();
        List<TranscriptSegment> segments = transcripts.listByVideoIds(videoIds);
        TranscriptVectorSearch.RebuildResult result = vectorSearch.rebuildIndex(segments);
        return new VectorIndexRebuildResponse(
                result.success(),
                result.vectorStoreMode(),
                result.indexName(),
                videoAssets.size(),
                result.segmentCount(),
                result.indexedCount(),
                result.message()
        );
    }

    public VectorIndexStatusResponse status() {
        QdrantVectorStore store = qdrant.getIfAvailable();
        if (store == null) {
            return new VectorIndexStatusResponse(
                    vectorSearch.vectorStoreMode(),
                    false,
                    vectorSearch.vectorStoreEndpoint(),
                    "",
                    false,
                    "memory",
                    0,
                    0,
                    0,
                    vectorSearch.dimensions(),
                    "",
                    "vector store mode is memory"
            );
        }

        QdrantVectorStore.InspectResult inspect = store.inspect();
        return new VectorIndexStatusResponse(
                store.mode(),
                inspect.connected(),
                store.baseUrl(),
                store.collectionName(),
                inspect.collectionExists(),
                inspect.collectionStatus(),
                inspect.pointsCount(),
                inspect.indexedVectorsCount(),
                inspect.segmentsCount(),
                inspect.dimensions(),
                inspect.distance(),
                inspect.message()
        );
    }
}
