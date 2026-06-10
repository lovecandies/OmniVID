package com.omnivid.api.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptContextRepairServiceTests {
    private final TranscriptContextRepairService repairService = new TranscriptContextRepairService(new SubtitleTextSanitizer());

    @Test
    void repairsAmbiguousTermsWhenVideoContextHasEvidence() {
        List<TranscriptSegment> segments = List.of(
                segment(1, "这一段在讲 Redis 缓存和分布式锁"),
                segment(2, "后面会讲 read is 的热点 key 和 setnx"),
                segment(3, "再接 MySQL 事务和唯一索引")
        );

        List<TranscriptContextRepairService.RepairPatch> patches = repairService.buildRepairPlan(segments);

        assertThat(patches).anySatisfy(patch -> {
            assertThat(patch.segmentId()).isEqualTo(2);
            assertThat(patch.text()).contains("Redis");
        });
    }

    @Test
    void avoidsAmbiguousRepairWithoutContextEvidence() {
        List<TranscriptSegment> segments = List.of(
                segment(1, "这一句话只是说 ready is a normal phrase"),
                segment(2, "没有任何数据库或缓存上下文")
        );

        List<TranscriptContextRepairService.RepairPatch> patches = repairService.buildRepairPlan(segments);

        assertThat(patches).isEmpty();
    }

    private TranscriptSegment segment(long id, String content) {
        return new TranscriptSegment(id, 1, (int) id, id * 1000, id * 1000 + 800, "Host", content, 1);
    }
}
