# OmniVid Vibe Coding Blueprint

## 项目目标

OmniVid 是一个面向 Java 后端开发和 AI Agent 求职的长视频知识解析项目。第一阶段不追求功能堆满，而是把每个功能都设计成可演示、可压测、可写简历、可应对八股追问的工程钩子。

核心叙事：

> 长视频上传和解析链路天然包含大文件 IO、异步任务、状态一致性、缓存、限流、检索、Agent 工具调用等后端高频考点。项目要把这些考点落在真实业务流中，而不是孤立背题。

## 第一阶段范围

必须做：

1. 本地视频上传与 MD5 去重。
2. MySQL 任务状态机。
3. Redis 防重复提交、分布式锁、任务进度缓存、限流。
4. 本地轻量 DAG 解析流程。
5. 时间轴字幕查询和点击跳转。
6. 知识库问答 Agent，回答必须带视频来源和时间戳。
7. 面试钩子文档和简历话术。

暂不做：

1. B站、YouTube、抖音、小红书等多平台 URL 解析。
2. 浏览器插件。
3. 企业级权限、组织、多租户、计费。
4. RocketMQ 首发实现。第一阶段只保留可升级接口和面试演进话术。

## 推荐目录结构

```text
E:\video
├── CODEX.md
├── docs
│   ├── 01-career-architecture.md
│   ├── 02-mysql-redis-hooks.md
│   └── 03-backend-agent-playbook.md
├── apps
│   ├── api
│   └── web
└── infra
```

当前交付先完成 `CODEX.md` 和 `docs`，后续实现代码时再创建 `apps` 与 `infra`。

## 技术路线

后端主线：

- Java 17/21
- Spring Boot 3.x
- MyBatis-Plus
- MySQL 8.x
- Redis / Redisson
- SSE
- 本地线程池 DAG，二阶段升级 RocketMQ

AI Agent 加分线：

- Ollama 本地 LLM / Embedding
- whisper.cpp 或本地 ASR
- Spring AI 或 LangChain4j
- RAG
- Agent Tool Calling
- 引用约束与低置信度拒答

## Vibe Coding 执行规则

每个功能必须同时产出三件东西：

1. 业务闭环：用户能在浏览器或终端看到结果。
2. 后端钩子：能对应至少一个 Java/MySQL/Redis/Spring/并发/MQ/网络八股点。
3. 面试话术：能按“业务痛点 -> 技术方案 -> 八股关键词 -> 可验证结果”讲清楚。

不要添加和求职叙事无关的功能。任何新功能如果不能形成可验证业务流或面试钩子，默认不做。

## 黑盒验证格式

复杂任务必须写成：

1. [执行步骤] -> 验证: [终端日志或浏览器变化]
2. [执行步骤] -> 验证: [数据库/Redis/页面可观察结果]

示例：

1. 上传同一视频两次 -> 验证: 页面第二次提示命中去重，MySQL 只有一条 `video_asset`，Redis 锁 Key 短暂存在后释放。
2. 启动解析任务 -> 验证: 页面通过 SSE 显示任务阶段变化，MySQL `processing_job.current_step` 顺序推进。
3. 点击字幕 -> 验证: 播放器跳转到对应秒数，后端查询命中 `(video_id, start_ms)` 联合索引。

## 面试总叙事

一句话介绍：

> 我做的是一个长视频 AI 知识解析系统。它不是简单调用大模型，而是围绕视频上传、异步解析、任务状态、字幕检索、知识库问答搭了一套 Java 后端工程链路。里面重点用了 MySQL 做状态一致性和索引优化，Redis 做防重、锁、进度缓存、限流和语义缓存，再通过 Agent 工具调用实现可追溯的视频问答。

遇到八股追问时，优先把问题拉回项目：

- 问 MySQL：讲视频去重、字幕索引、任务状态机。
- 问 Redis：讲防重复提交、进度缓存、限流、语义缓存。
- 问 JUC：讲本地 DAG 线程池、异步任务、并发上传。
- 问 JVM：讲大文件上传、字幕切片、OOM 和排查。
- 问 Spring：讲事务失效、Filter/Interceptor/AOP、Bean 策略路由。
- 问 MQ：讲本地 DAG 如何演进到 RocketMQ。
- 问网络：讲 SSE、HTTP 长连接、断线重连、分片上传。
- 问 Agent：讲 RAG、工具调用、引用约束、防幻觉。
