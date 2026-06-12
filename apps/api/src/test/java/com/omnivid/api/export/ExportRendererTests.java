package com.omnivid.api.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ExportRendererTests {
    private final ExportRenderer renderer = new ExportRenderer();
    private final ExportDocument document = new ExportDocument(
            "OmniVid 会议纪要",
            "长视频知识解析会议",
            "本次会议确认了异步解析、字幕检索和 Agent 引用的核心方案。",
            List.of(
                    new ExportSection("会议目标", List.of("明确版本目标", "确认交付边界")),
                    new ExportSection("关键决策", List.of("采用 MySQL Outbox", "回答必须带时间戳引用"))
            ),
            List.of("完成部署验收", "复核字幕质量"),
            List.of("[00:12-00:36] 讨论长视频解析目标")
    );

    @Test
    void rendersDetailedMarkdown() {
        String markdown = new String(renderer.render(document, ExportFormat.MARKDOWN), StandardCharsets.UTF_8);

        assertThat(markdown).contains("# OmniVid 会议纪要", "## 执行摘要", "## 关键决策", "## 来源片段");
    }

    @Test
    void rendersValidDocx() throws Exception {
        byte[] bytes = renderer.render(document, ExportFormat.DOCX);

        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(docx.getParagraphs()).hasSizeGreaterThan(8);
            assertThat(docx.getParagraphs().stream().map(paragraph -> paragraph.getText()).toList())
                    .contains("OmniVid 会议纪要", "执行摘要", "关键决策", "来源片段");
        }
    }

    @Test
    void rendersValidPptx() throws Exception {
        byte[] bytes = renderer.render(document, ExportFormat.PPTX);

        try (XMLSlideShow pptx = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(pptx.getSlides()).hasSizeGreaterThanOrEqualTo(6);
            assertThat(pptx.getSlides().getFirst().getShapes()).isNotEmpty();
        }
    }
}
