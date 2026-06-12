# RocketMQ Processing Contract

该目录只负责视频解析命令的调度可靠性，不实现 ASR、OCR、总结或向量检索业务。

## 约束

1. 消息体只传轻量业务标识，不传视频、音频或字幕正文。
2. 任务与 `processing_event` 必须在同一个事务中写入，RocketMQ 发布只能读取已提交的 Outbox 事件。
3. Consumer 必须用 `eventId` 做幂等判断，并通过数据库 CAS 进入 `CONSUMING`。
4. 本地模式与 RocketMQ 模式必须调用同一个 `VideoService.runProcessingCommand`。
5. 发布失败不能把任务直接标记失败；事件保留在 Outbox 等待恢复。
6. 消费达到最大重试次数后进入应用级 DLQ，必须允许人工重投。
7. Publisher 不得覆盖 `CONSUMING/CONSUMED/DLQ` 等消费者已推进的状态。

## 验证

```text
GET /api/jobs/mq/status
GET /api/jobs/events?status=DLQ
POST /api/jobs/events/{eventId}/retry
```
