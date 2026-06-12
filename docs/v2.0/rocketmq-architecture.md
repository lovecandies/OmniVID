# OmniVid 2.0 RocketMQ 可靠异步架构

更新时间：2026-06-12

## 目标

将视频解析从进程内线程池调度升级为可恢复的 RocketMQ 异步调度，同时保留现有解析 DAG 的步骤状态、SSE 进度和本地开发降级能力。

## 当前边界与取舍

- RocketMQ 负责可靠调度完整视频解析命令。
- ASR、OCR、总结和向量索引仍在同一个 Worker 消费单元内执行，继续通过 `processing_job.current_step` 展示细粒度进度。
- 不把 WAV、ASR JSON 等大文件放进消息；消息只包含 `eventId`、`videoId`、`jobId` 和是否替换字幕。
- Docker 模式默认使用 RocketMQ；默认 H2 模式继续使用本地线程池，保证测试和轻量开发可运行。
- 本轮使用应用级 DLQ 表记录最终失败事件并支持重投。后续拆分独立 Worker 时，可以继续映射到 Broker 原生 DLQ。

## 数据流

```text
上传完成 / 失败重试 / ASR 重跑
  -> 同一 MySQL 事务写入 processing_job + processing_event(PENDING)
  -> Outbox Publisher
  -> RocketMQ omnivid-processing / PROCESS_VIDEO
  -> Processing Consumer
  -> processing_event(CONSUMING)
  -> VideoService 解析 DAG
  -> processing_event(CONSUMED)
```

失败路径：

```text
发布失败 -> processing_event(PUBLISH_FAILED)，按 2-60 秒退避重投
消费失败 -> RocketMQ 重试
达到最大次数 -> processing_event(DLQ)
人工重投 -> processing_event(PENDING)
```

## 幂等与一致性

- `processing_event.event_id` 是全局消息幂等键。
- `processing_event(job_id, event_type)` 唯一约束防止同一任务重复创建解析命令。
- Consumer 执行前通过数据库 CAS 将事件置为 `CONSUMING`，并发重复消息无法同时运行同一任务。
- 应用重启时将中断的 `CONSUMING` 恢复为 `PUBLISHED`，由 Broker 继续投递。
- Publisher 只允许把 `PENDING/PUBLISH_FAILED` 更新为 `PUBLISHED`，不会覆盖消费者已经推进的状态。
- 消费成功后更新事件状态；重复投递直接返回成功，不重复写字幕和总结。
- 字幕表、总结表已有业务唯一键，作为最终数据层兜底。

## 运行模式

```yaml
omnivid:
  processing:
    mode: local | rocketmq
    rocketmq:
      namesrv-addr: localhost:9876
      topic: omnivid-processing
      producer-group: omnivid-processing-producer
      consumer-group: omnivid-processing-consumer
      max-reconsume-times: 3
```

## 黑盒验收

1. `GET /api/runtime/status` 显示 `processing.mode=rocketmq`、`connected=true`。
2. 创建解析任务后，`processing_event` 从 `PENDING` 进入 `PUBLISHED`，最终进入 `CONSUMED`。
3. 重复发送同一个 `eventId` 不会重复生成字幕或总结。
4. Broker 暂停期间事件保留在 MySQL；恢复 Broker 后事件继续投递。
5. 达到最大重试次数的事件进入 DLQ，并可通过 API 重新入队。
