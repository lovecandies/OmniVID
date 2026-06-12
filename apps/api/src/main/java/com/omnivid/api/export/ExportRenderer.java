package com.omnivid.api.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

@Service
public class ExportRenderer {
    private static final Color SLIDE_BACKGROUND = new Color(19, 25, 31);
    private static final Color SLIDE_ACCENT = new Color(45, 212, 191);
    private static final Color SLIDE_TEXT = new Color(225, 232, 238);
    private static final Color SLIDE_MUTED = new Color(154, 167, 179);

    public byte[] render(ExportDocument document, ExportFormat format) {
        try {
            return switch (format) {
                case MARKDOWN -> markdown(document).getBytes(StandardCharsets.UTF_8);
                case DOCX -> docx(document);
                case PPTX -> pptx(document);
            };
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to render export file", exception);
        }
    }

    private String markdown(ExportDocument document) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(document.title()).append("\n\n");
        if (!document.subtitle().isBlank()) {
            markdown.append("> ").append(document.subtitle()).append("\n\n");
        }
        markdown.append("## 执行摘要\n\n").append(document.executiveSummary()).append("\n\n");
        for (ExportSection section : document.sections()) {
            markdown.append("## ").append(section.heading()).append("\n\n");
            for (String bullet : section.bullets()) {
                markdown.append("- ").append(bullet).append('\n');
            }
            markdown.append('\n');
        }
        appendMarkdownList(markdown, "行动项", document.actionItems());
        appendMarkdownList(markdown, "来源片段", document.sourceNotes());
        markdown.append("---\n由 OmniVid 基于视频字幕生成。\n");
        return markdown.toString();
    }

    private void appendMarkdownList(StringBuilder markdown, String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        markdown.append("## ").append(heading).append("\n\n");
        for (String item : items) {
            markdown.append("- ").append(item).append('\n');
        }
        markdown.append('\n');
    }

    private byte[] docx(ExportDocument document) throws IOException {
        try (XWPFDocument output = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            addDocParagraph(output, document.title(), 24, true, ParagraphAlignment.CENTER);
            addDocParagraph(output, document.subtitle(), 11, false, ParagraphAlignment.CENTER);
            addDocHeading(output, "执行摘要");
            addDocParagraph(output, document.executiveSummary(), 11, false, ParagraphAlignment.BOTH);
            for (ExportSection section : document.sections()) {
                addDocHeading(output, section.heading());
                for (String bullet : section.bullets()) {
                    addDocParagraph(output, "• " + bullet, 11, false, ParagraphAlignment.LEFT);
                }
            }
            addDocList(output, "行动项", document.actionItems());
            addDocList(output, "来源片段", document.sourceNotes());
            addDocParagraph(output, "由 OmniVid 基于视频字幕生成。", 9, false, ParagraphAlignment.CENTER);
            output.write(bytes);
            return bytes.toByteArray();
        }
    }

    private void addDocHeading(XWPFDocument document, String text) {
        addDocParagraph(document, text, 15, true, ParagraphAlignment.LEFT);
    }

    private void addDocList(XWPFDocument document, String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        addDocHeading(document, heading);
        for (String item : items) {
            addDocParagraph(document, "• " + item, 11, false, ParagraphAlignment.LEFT);
        }
    }

    private void addDocParagraph(
            XWPFDocument document,
            String text,
            int fontSize,
            boolean bold,
            ParagraphAlignment alignment
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        paragraph.setSpacingAfter(150);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(fontSize);
        run.setBold(bold);
    }

    private byte[] pptx(ExportDocument document) throws IOException {
        try (XMLSlideShow output = new XMLSlideShow(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            output.setPageSize(new Dimension(1280, 720));
            addTitleSlide(output, document);
            addContentSlide(output, "执行摘要", List.of(document.executiveSummary()), "OmniVid Summary");
            for (ExportSection section : document.sections()) {
                for (List<String> bullets : chunks(section.bullets(), 5)) {
                    addContentSlide(output, section.heading(), bullets, "Video evidence driven");
                }
            }
            if (!document.actionItems().isEmpty()) {
                addContentSlide(output, "行动项", document.actionItems(), "Next actions");
            }
            if (!document.sourceNotes().isEmpty()) {
                for (List<String> notes : chunks(document.sourceNotes(), 5)) {
                    addContentSlide(output, "来源片段", notes, "Traceable citations");
                }
            }
            output.write(bytes);
            return bytes.toByteArray();
        }
    }

    private void addTitleSlide(XMLSlideShow output, ExportDocument document) {
        XSLFSlide slide = output.createSlide();
        addBackground(slide);
        addText(slide, document.title(), 72, 145, 1136, 170, 34, true, SLIDE_TEXT, TextParagraph.TextAlign.LEFT);
        addText(slide, document.subtitle(), 72, 335, 1000, 80, 18, false, SLIDE_MUTED, TextParagraph.TextAlign.LEFT);
        addText(slide, "OMNIVID · VIDEO KNOWLEDGE EXPORT", 72, 600, 1000, 40, 11, true, SLIDE_ACCENT, TextParagraph.TextAlign.LEFT);
    }

    private void addContentSlide(XMLSlideShow output, String heading, List<String> bullets, String eyebrow) {
        XSLFSlide slide = output.createSlide();
        addBackground(slide);
        addText(slide, eyebrow.toUpperCase(), 68, 48, 1050, 32, 10, true, SLIDE_ACCENT, TextParagraph.TextAlign.LEFT);
        addText(slide, heading, 68, 92, 1120, 80, 28, true, SLIDE_TEXT, TextParagraph.TextAlign.LEFT);
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(76, 195, 1110, 440));
        box.setWordWrap(true);
        box.setVerticalAlignment(VerticalAlignment.TOP);
        for (String bullet : bullets) {
            XSLFTextParagraph paragraph = box.addNewTextParagraph();
            paragraph.setBullet(true);
            paragraph.setLeftMargin(24d);
            paragraph.setIndent(-12d);
            paragraph.setSpaceAfter(14d);
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText(bullet);
            run.setFontFamily("Microsoft YaHei");
            run.setFontSize(18d);
            run.setFontColor(SLIDE_TEXT);
        }
        addText(slide, "Generated by OmniVid", 1040, 670, 180, 24, 9, false, SLIDE_MUTED, TextParagraph.TextAlign.RIGHT);
    }

    private void addBackground(XSLFSlide slide) {
        XSLFAutoShape background = slide.createAutoShape();
        background.setShapeType(ShapeType.RECT);
        background.setAnchor(new Rectangle2D.Double(0, 0, 1280, 720));
        background.setFillColor(SLIDE_BACKGROUND);
        background.setLineColor(SLIDE_BACKGROUND);
    }

    private void addText(
            XSLFSlide slide,
            String text,
            double x,
            double y,
            double width,
            double height,
            double fontSize,
            boolean bold,
            Color color,
            TextParagraph.TextAlign alignment
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, width, height));
        box.setWordWrap(true);
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(alignment);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(text);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontColor(color);
    }

    private List<List<String>> chunks(List<String> source, int size) {
        List<List<String>> chunks = new ArrayList<>();
        for (int index = 0; index < source.size(); index += size) {
            chunks.add(source.subList(index, Math.min(index + size, source.size())));
        }
        return chunks;
    }
}
