# Benchmarks

These numbers are representative local benchmark results collected on a development machine. They are intended to show the scale of the exercised workflow rather than universal performance guarantees.

## Long-Video Upload

| Metric | Result |
| --- | --- |
| Test video | 139 MB, about 76 minutes |
| Chunk size | 5 MB |
| Chunk count | 27 |
| Average chunk upload | 144 ms |
| Merge and job creation | 1.24 s |
| Duplicate upload reuse | 134 ms |

The duplicate path reuses the existing MD5 asset and avoids repeated parsing and model calls.

## Processing Pipeline

| Stage | Result |
| --- | --- |
| Full background processing | about 798 s |
| ffmpeg audio extraction | about 136 s |
| Whisper.cpp transcription | about 464.6 s |
| VAD optimization sample | invalid audio reduced by about 11.9% |

The upload request returns after task creation, while ffmpeg, ASR, summary generation and vector indexing continue asynchronously.

## Agent Cache

| Scenario | Result |
| --- | --- |
| First answer | 234 ms |
| Same-question cache hit | 39 ms |
| Reduction | about 83% |

Redis cache is used to reduce repeated Agent calls while keeping MySQL and Qdrant as the durable evidence layers.

