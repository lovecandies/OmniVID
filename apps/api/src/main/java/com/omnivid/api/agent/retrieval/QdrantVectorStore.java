package com.omnivid.api.agent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.transcript.TranscriptSegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.vector-store", name = "mode", havingValue = "qdrant")
public class QdrantVectorStore {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final int UPSERT_BATCH_SIZE = 64;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String collectionName;
    private final Duration timeout;
    private final ConcurrentHashMap<Long, Integer> indexedContentHash = new ConcurrentHashMap<>();
    private volatile int readyDimensions;

    public QdrantVectorStore(
            ObjectMapper objectMapper,
            @Value("${omnivid.vector-store.qdrant.base-url:http://localhost:6333}") String baseUrl,
            @Value("${omnivid.vector-store.qdrant.collection:omnivid_transcript_segments}") String collectionName,
            @Value("${omnivid.vector-store.qdrant.timeout:5s}") Duration timeout
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.collectionName = collectionName;
        this.timeout = timeout;
    }

    public String mode() {
        return "qdrant";
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String collectionName() {
        return collectionName;
    }

    public String indexName() {
        return "qdrant:" + collectionName;
    }

    public boolean connected() {
        try {
            HttpResponse<String> response = send("GET", "/collections", null);
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception exception) {
            return false;
        }
    }

    public InspectResult inspect() {
        try {
            HttpResponse<String> response = send("GET", "/collections/" + collectionName, null);
            if (response.statusCode() == 404) {
                return new InspectResult(
                        true,
                        false,
                        "missing",
                        0,
                        0,
                        0,
                        0,
                        "",
                        "collection not created yet"
                );
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new InspectResult(
                        false,
                        false,
                        "unavailable",
                        0,
                        0,
                        0,
                        0,
                        "",
                        "qdrant status failed with HTTP " + response.statusCode()
                );
            }
            JsonNode result = objectMapper.readTree(response.body()).path("result");
            JsonNode vectors = result.path("config").path("params").path("vectors");
            return new InspectResult(
                    true,
                    true,
                    result.path("status").asText("unknown"),
                    result.path("points_count").asLong(0),
                    result.path("indexed_vectors_count").asLong(0),
                    result.path("segments_count").asInt(0),
                    vectors.path("size").asInt(0),
                    vectors.path("distance").asText(""),
                    result.path("optimizer_status").asText("unknown")
            );
        } catch (Exception exception) {
            return new InspectResult(
                    false,
                    false,
                    "unavailable",
                    0,
                    0,
                    0,
                    0,
                    "",
                    exception.getMessage()
            );
        }
    }

    public Optional<List<SearchHit>> search(
            List<TranscriptSegment> segments,
            Map<Integer, Double> queryVector,
            Map<Long, Map<Integer, Double>> segmentVectors,
            int dimensions,
            int limit
    ) {
        if (segments.isEmpty() || queryVector.isEmpty() || dimensions <= 0) {
            return Optional.of(List.of());
        }
        try {
            if (!ensureCollection(dimensions)) {
                return Optional.empty();
            }
            upsertMissingSegments(segments, segmentVectors, dimensions);

            Set<Long> videoIds = segments.stream()
                    .map(TranscriptSegment::videoId)
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("vector", denseVector(queryVector, dimensions));
            payload.put("limit", Math.max(limit, 1));
            payload.put("with_payload", true);
            payload.put("filter", Map.of(
                    "must", List.of(Map.of(
                            "key", "videoId",
                            "match", Map.of("any", videoIds.stream().sorted().toList())
                    ))
            ));

            HttpResponse<String> response = send("POST", "/collections/" + collectionName + "/points/search", payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Qdrant search failed with status {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode result = objectMapper.readTree(response.body()).path("result");
            if (!result.isArray()) {
                return Optional.of(List.of());
            }
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode item : result) {
                long segmentId = item.path("payload").path("segmentId").asLong(item.path("id").asLong(0));
                double score = item.path("score").asDouble(0);
                if (segmentId > 0) {
                    hits.add(new SearchHit(segmentId, score));
                }
            }
            return Optional.of(hits);
        } catch (Exception exception) {
            log.warn("Qdrant vector search unavailable, falling back to local memory: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public boolean rebuild(
            List<TranscriptSegment> segments,
            Map<Long, Map<Integer, Double>> segmentVectors,
            int dimensions
    ) {
        if (dimensions <= 0) {
            return false;
        }
        try {
            if (!recreateCollection(dimensions)) {
                return false;
            }
            upsertMissingSegments(segments, segmentVectors, dimensions);
            return true;
        } catch (Exception exception) {
            log.warn("Qdrant rebuild failed: {}", exception.getMessage());
            return false;
        }
    }

    private boolean ensureCollection(int dimensions) throws Exception {
        if (readyDimensions == dimensions) {
            return true;
        }

        HttpResponse<String> get = send("GET", "/collections/" + collectionName, null);
        if (get.statusCode() >= 200 && get.statusCode() < 300) {
            int existingDimensions = objectMapper.readTree(get.body())
                    .path("result")
                    .path("config")
                    .path("params")
                    .path("vectors")
                    .path("size")
                    .asInt(0);
            if (existingDimensions > 0 && existingDimensions != dimensions) {
                log.warn("Qdrant collection {} dimensions {} do not match embedding dimensions {}",
                        collectionName, existingDimensions, dimensions);
                HttpResponse<String> delete = send("DELETE", "/collections/" + collectionName + "?timeout=30", null);
                if (delete.statusCode() < 200 || delete.statusCode() >= 300) {
                    return false;
                }
                indexedContentHash.clear();
                readyDimensions = 0;
                return createCollection(dimensions);
            }
            readyDimensions = dimensions;
            return true;
        }
        if (get.statusCode() != 404) {
            return false;
        }

        return createCollection(dimensions);
    }

    private boolean createCollection(int dimensions) throws Exception {
        Map<String, Object> payload = Map.of(
                "vectors", Map.of(
                        "size", dimensions,
                        "distance", "Cosine"
                )
        );
        HttpResponse<String> put = send("PUT", "/collections/" + collectionName, payload);
        boolean created = put.statusCode() >= 200 && put.statusCode() < 300;
        if (created) {
            readyDimensions = dimensions;
        }
        return created;
    }

    private boolean recreateCollection(int dimensions) throws Exception {
        HttpResponse<String> delete = send("DELETE", "/collections/" + collectionName + "?timeout=30", null);
        if (delete.statusCode() != 404 && (delete.statusCode() < 200 || delete.statusCode() >= 300)) {
            return false;
        }
        indexedContentHash.clear();
        readyDimensions = 0;
        return createCollection(dimensions);
    }

    private void upsertMissingSegments(
            List<TranscriptSegment> segments,
            Map<Long, Map<Integer, Double>> segmentVectors,
            int dimensions
    ) throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (TranscriptSegment segment : segments) {
            int contentHash = contentHash(segment);
            Integer indexedHash = indexedContentHash.get(segment.id());
            if (indexedHash != null && indexedHash == contentHash) {
                continue;
            }

            Map<Integer, Double> vector = segmentVectors.getOrDefault(segment.id(), Map.of());
            if (vector.isEmpty()) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", segment.id());
            point.put("vector", denseVector(vector, dimensions));
            point.put("payload", Map.of(
                    "segmentId", segment.id(),
                    "videoId", segment.videoId(),
                    "contentHash", contentHash,
                    "segmentIndex", segment.segmentIndex(),
                    "startMs", segment.startMs(),
                    "endMs", segment.endMs()
            ));
            batch.add(point);
            indexedContentHash.put(segment.id(), contentHash);

            if (batch.size() >= UPSERT_BATCH_SIZE) {
                upsertBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            upsertBatch(batch);
        }
    }

    private void upsertBatch(List<Map<String, Object>> points) throws Exception {
        Map<String, Object> payload = Map.of("points", points);
        HttpResponse<String> response = send("PUT", "/collections/" + collectionName + "/points?wait=true", payload);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Qdrant upsert failed with status " + response.statusCode());
        }
    }

    private List<Double> denseVector(Map<Integer, Double> sparse, int dimensions) {
        List<Double> vector = new ArrayList<>(java.util.Collections.nCopies(dimensions, 0.0));
        for (Map.Entry<Integer, Double> entry : sparse.entrySet()) {
            int index = entry.getKey();
            if (index >= 0 && index < dimensions) {
                vector.set(index, entry.getValue());
            }
        }
        return vector;
    }

    private int contentHash(TranscriptSegment segment) {
        return (segment.content() == null ? "" : segment.content()).hashCode();
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            String payload = body == null ? "" : objectMapper.writeValueAsString(body);
            builder.method(method, HttpRequest.BodyPublishers.ofString(payload));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank() ? "http://localhost:6333" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record SearchHit(long segmentId, double score) {
    }

    public record InspectResult(
            boolean connected,
            boolean collectionExists,
            String collectionStatus,
            long pointsCount,
            long indexedVectorsCount,
            int segmentsCount,
            int dimensions,
            String distance,
            String message
    ) {
    }
}
