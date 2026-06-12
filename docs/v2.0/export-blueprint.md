# OmniVid PPTX / DOCX / Markdown 真实导出

更新时间：2026-06-12

## 架构蓝图

```text
当前视频
  -> 时间轴字幕 + 所选结构化总结
  -> DeepSeek 生成详细 ExportDocument
  -> JSON 结构校验
  -> Markdown / DOCX / PPTX Renderer
  -> 浏览器下载
```

## 内容约束

- 会议纪要强调议题、讨论、决策、行动项和未决问题。
- 博客强调引言、核心论述、案例、结论和延伸思考。
- PPT 强调逐页标题、要点、证据和结论。
- 核心观点强调结论、论据、影响和建议。
- 所有格式保留视频时间戳来源片段。

## 降级策略

DeepSeek 未启用、请求失败或 JSON 不完整时，服务端会使用已有总结与字幕构建本地详细文档，仍然生成真实文件。响应头 `X-OmniVid-Generation-Mode` 标识 `deepseek` 或 `local-fallback`。

## 接口

```http
POST /api/videos/{videoId}/exports
Content-Type: application/json

{
  "summaryType": "MEETING_MINUTES",
  "format": "DOCX"
}
```

支持格式：`MARKDOWN`、`DOCX`、`PPTX`。

## 已完成验收

- Docker 模式真实调用 `deepseek-chat` 生成详细会议纪要。
- Markdown 包含 9 个章节以及行动项和来源片段。
- DOCX 可打开并包含标题、行动项和来源片段。
- PPTX 可打开并生成 13 页完整演示稿。
- 同一字幕版本连续导出三种格式时只调用一次 DeepSeek，后续格式复用生成结果。
