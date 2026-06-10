package com.omnivid.api.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubtitleTextSanitizerTests {
    private final SubtitleTextSanitizer sanitizer = new SubtitleTextSanitizer();

    @Test
    void normalizesCommonAsrTechnicalTermErrors() {
        String text = sanitizer.normalizeTranscript(
                "my sql 和 red is 可以放进 R A G 链路，a p i 调 deep seek，再用 cloud code 和 code x 写代码"
        );

        assertThat(text).contains("MySQL");
        assertThat(text).contains("Redis");
        assertThat(text).contains("RAG");
        assertThat(text).contains("API");
        assertThat(text).contains("DeepSeek");
        assertThat(text).contains("Claude Code");
        assertThat(text).contains("Codex");
    }

    @Test
    void keepsOrdinaryChineseTextStable() {
        String text = sanitizer.normalizeTranscript("这个视频主要介绍长视频上传、去重、字幕检索和智能问答。");

        assertThat(text).isEqualTo("这个视频主要介绍长视频上传、去重、字幕检索和智能问答。");
    }

    @Test
    void repairsTranscriptTermsWhenReadingExistingRows() {
        SubtitleTextSanitizer.RepairResult result = sanitizer.repairTranscriptIfBetter("这里使用 a s r 和 o c r 修复 j v m 相关字幕");

        assertThat(result.changed()).isTrue();
        assertThat(result.text()).contains("ASR", "OCR", "JVM");
    }

    @Test
    void normalizesInterviewHotwordsForAsrOutput() {
        String text = sanitizer.normalizeTranscript(
                "ready son 做分布式锁，set n x 防重复，q drant 存 im bedding，re rank 后调用 rocket m q"
        );

        assertThat(text).contains("Redisson");
        assertThat(text).contains("SETNX");
        assertThat(text).contains("Qdrant");
        assertThat(text).contains("Embedding");
        assertThat(text).contains("Rerank");
        assertThat(text).contains("RocketMQ");
    }

    @Test
    void normalizesTraditionalPronounAndAgentAsrErrors() {
        String text = sanitizer.normalizeTranscript("妳可以让 AIGENT 和 AI-ZEN 解释视频内容");

        assertThat(text).contains("你");
        assertThat(text).doesNotContain("妳");
        assertThat(text).contains("AI Agent");
        assertThat(text).doesNotContain("AIGENT", "AI-ZEN");
    }
}
