package com.omnivid.api.agent.retrieval;

import com.omnivid.api.transcript.TranscriptSegment;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class TranscriptVectorSearch {
    private static final double MIN_SCORE = 0.08;

    private final ConcurrentHashMap<Long, CachedVector> segmentIndex = new ConcurrentHashMap<>();
    private final EmbeddingProvider embeddingProvider;
    private final ObjectProvider<QdrantVectorStore> qdrant;

    public TranscriptVectorSearch(EmbeddingProvider embeddingProvider, ObjectProvider<QdrantVectorStore> qdrant) {
        this.embeddingProvider = embeddingProvider;
        this.qdrant = qdrant;
    }

    public String indexName() {
        QdrantVectorStore store = qdrant.getIfAvailable();
        if (store != null && store.connected()) {
            return store.indexName();
        }
        return embeddingProvider.providerName() + "-memory-cache";
    }

    public String vectorStoreMode() {
        return qdrant.getIfAvailable() == null ? "memory" : "qdrant";
    }

    public boolean vectorStoreConnected() {
        QdrantVectorStore store = qdrant.getIfAvailable();
        return store != null && store.connected();
    }

    public String vectorStoreEndpoint() {
        QdrantVectorStore store = qdrant.getIfAvailable();
        return store == null ? "memory" : store.baseUrl();
    }

    public String providerName() {
        return embeddingProvider.providerName();
    }

    public int dimensions() {
        return embeddingProvider.dimensions();
    }

    public String embeddingDiagnostic() {
        return embeddingProvider.diagnostic();
    }

    public List<VectorCandidate> search(List<TranscriptSegment> segments, String query, int limit) {
        Map<Integer, Double> queryVector = embeddingProvider.embed(query);
        if (queryVector.isEmpty() || segments.isEmpty()) {
            return List.of();
        }

        QdrantVectorStore store = qdrant.getIfAvailable();
        if (store != null) {
            Map<Long, Map<Integer, Double>> segmentVectors = segments.stream()
                    .collect(Collectors.toMap(
                            TranscriptSegment::id,
                            this::segmentVector,
                            (left, right) -> left
                    ));
            Optional<List<QdrantVectorStore.SearchHit>> hits = store.search(
                    segments,
                    queryVector,
                    segmentVectors,
                    embeddingProvider.dimensions(),
                    limit
            );
            if (hits.isPresent()) {
                Map<Long, TranscriptSegment> byId = segments.stream()
                        .collect(Collectors.toMap(TranscriptSegment::id, Function.identity(), (left, right) -> left));
                return hits.get().stream()
                        .map(hit -> {
                            TranscriptSegment segment = byId.get(hit.segmentId());
                            return segment == null ? null : new VectorCandidate(segment, hit.score());
                        })
                        .filter(candidate -> candidate != null && candidate.score() >= MIN_SCORE)
                        .sorted(Comparator.comparingDouble(VectorCandidate::score).reversed()
                                .thenComparing(candidate -> candidate.segment().startMs()))
                        .limit(limit)
                        .toList();
            }
        }

        return segments.stream()
                .map(segment -> new VectorCandidate(segment, cosine(queryVector, segmentVector(segment))))
                .filter(candidate -> candidate.score() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(VectorCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.segment().startMs()))
                .limit(limit)
                .toList();
    }

    public RebuildResult rebuildIndex(List<TranscriptSegment> segments) {
        QdrantVectorStore store = qdrant.getIfAvailable();
        if (store == null) {
            return new RebuildResult(false, vectorStoreMode(), indexName(), segments.size(), 0, "vector store mode is memory");
        }
        if (!store.connected()) {
            return new RebuildResult(false, store.mode(), store.indexName(), segments.size(), 0, "qdrant unavailable");
        }
        if (segments.isEmpty()) {
            boolean rebuilt = store.rebuild(List.of(), Map.of(), embeddingProvider.dimensions());
            String message = rebuilt ? "qdrant index rebuilt with no transcript segments" : "qdrant rebuild failed";
            return new RebuildResult(rebuilt, store.mode(), store.indexName(), 0, 0, message);
        }

        Map<Long, Map<Integer, Double>> segmentVectors = segments.stream()
                .collect(Collectors.toMap(
                        TranscriptSegment::id,
                        this::segmentVector,
                        (left, right) -> left
                ));
        boolean rebuilt = store.rebuild(segments, segmentVectors, embeddingProvider.dimensions());
        if (!rebuilt) {
            return new RebuildResult(false, store.mode(), store.indexName(), segments.size(), 0, "qdrant rebuild failed");
        }
        int indexed = (int) segmentVectors.values().stream().filter(vector -> !vector.isEmpty()).count();
        return new RebuildResult(true, store.mode(), store.indexName(), segments.size(), indexed, "qdrant index rebuilt");
    }

    private Map<Integer, Double> segmentVector(TranscriptSegment segment) {
        String content = segment.content() == null ? "" : segment.content();
        int contentHash = content.hashCode();
        CachedVector cached = segmentIndex.get(segment.id());
        if (cached != null && cached.contentHash() == contentHash) {
            return cached.vector();
        }
        Map<Integer, Double> vector = Map.copyOf(embeddingProvider.embed(content));
        segmentIndex.put(segment.id(), new CachedVector(contentHash, vector));
        return vector;
    }

    private double cosine(Map<Integer, Double> left, Map<Integer, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<Integer, Double> entry : left.entrySet()) {
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
        }
        double leftNorm = norm(left);
        double rightNorm = norm(right);
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (leftNorm * rightNorm);
    }

    private double norm(Map<Integer, Double> vector) {
        double sum = 0;
        for (double value : vector.values()) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    public record VectorCandidate(TranscriptSegment segment, double score) {
    }

    public record RebuildResult(
            boolean success,
            String vectorStoreMode,
            String indexName,
            int segmentCount,
            int indexedCount,
            String message
    ) {
    }

    private record CachedVector(int contentHash, Map<Integer, Double> vector) {
    }
}
