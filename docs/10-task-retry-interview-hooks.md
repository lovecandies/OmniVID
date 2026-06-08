# OmniVid 失败任务补偿与重试面试钩子

## 已实现能力

OmniVid 已支持失败视频解析任务的手动补偿重试：

- 后端接口：`POST /api/videos/{videoId}/retry`
- 前端入口：选中 `FAILED` 视频后，上传面板显示 `重试解析`
- 状态变化：`FAILED -> PROCESSING`，并创建新的 `processing_job`
- 重试计数：新任务写入 `retry_count = 上一次 retry_count + 1`
- 进度推送：复用原来的 Redis 进度缓存和 SSE
- DAG 执行：重新进入 `ffmpeg -> ASR -> 总结` 链路

## 面试回答模板

```text
业务痛点：
长视频解析是异步长耗时任务，ffmpeg、ASR 或云端模型都可能失败。如果失败只写日志，用户会以为系统卡死，也无法恢复任务。

技术方案：
我把解析任务抽象成 processing_job 状态机。失败时写入 FAILED、失败阶段和 error_message。用户可以对 FAILED 视频触发 retry，后端会基于原始 storage_path 重新加载文件，创建新的 RUNNING job，并把任务重新投递到本地 DAG 线程池。

八股关键词：
异步异常处理、任务状态机、幂等、乐观锁 version、retry_count、线程池投递、失败补偿、死信队列演进。

可验证结果：
调用 POST /api/videos/{videoId}/retry 后，页面会看到新 job 进入 RETRY_QUEUED/RUNNING，随后通过 SSE 推进到 AUDIO_EXTRACTING、ASR_TRANSCRIBING 或再次 FAILED。
```

## 简历埋钩子写法

```text
设计长视频解析任务失败补偿机制，基于 processing_job 状态机、retry_count 和 Redis 进度缓存实现 FAILED 任务的手动重试，并复用异步 DAG 重新执行 ffmpeg、ASR 与总结生成链路。
```

## 后续演进话术

```text
当前版本是手动补偿重试，适合 MVP 演示和问题定位。生产环境可以继续扩展为定时扫描 idx_job_status_updated(status, updated_at)，对失败任务做有限次数自动补偿；如果升级 RocketMQ，可以把失败消息投递到延迟队列或死信队列，但最终幂等仍由 MySQL 状态机兜底。
```
