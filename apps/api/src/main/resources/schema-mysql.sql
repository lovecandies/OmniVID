CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(160) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS video_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  md5 CHAR(32) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT uk_video_md5 UNIQUE (md5),
  KEY idx_video_user_created (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS processing_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  current_step VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  progress INT NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000),
  started_at TIMESTAMP NULL,
  finished_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  KEY idx_job_video (video_id),
  KEY idx_job_status_updated (status, updated_at)
);

CREATE TABLE IF NOT EXISTS processing_event (
  event_id VARCHAR(64) PRIMARY KEY,
  job_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000),
  next_attempt_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  consumed_at TIMESTAMP NULL,
  CONSTRAINT uk_processing_event_job_type UNIQUE (job_id, event_type),
  KEY idx_processing_event_status_updated (status, updated_at)
);

CREATE TABLE IF NOT EXISTS transcript_segment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  segment_index INT NOT NULL,
  start_ms BIGINT NOT NULL,
  end_ms BIGINT NOT NULL,
  speaker VARCHAR(80) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  token_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_transcript_video_index UNIQUE (video_id, segment_index),
  KEY idx_transcript_video_start (video_id, start_ms),
  KEY idx_transcript_video_time_cover (video_id, start_ms, end_ms, segment_index)
);

CREATE TABLE IF NOT EXISTS transcript_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  source VARCHAR(40) NOT NULL,
  note VARCHAR(255) NOT NULL DEFAULT '',
  snapshot_json LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_transcript_version_no UNIQUE (video_id, version_no),
  KEY idx_transcript_version_video_created (video_id, created_at)
);

CREATE TABLE IF NOT EXISTS summary_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(255) NOT NULL,
  content_json LONGTEXT NOT NULL,
  model_name VARCHAR(80) NOT NULL,
  prompt_version VARCHAR(40) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_summary_video_type UNIQUE (video_id, type)
);

CREATE TABLE IF NOT EXISTS chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  citation VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_base (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_knowledge_base_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS knowledge_base_video (
  knowledge_base_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_knowledge_base_video UNIQUE (knowledge_base_id, video_id),
  KEY idx_kb_video_video (video_id, knowledge_base_id)
);

CREATE TABLE IF NOT EXISTS llm_provider_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider_name VARCHAR(80) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  model VARCHAR(120) NOT NULL,
  api_key_encoded LONGTEXT NOT NULL,
  api_key_masked VARCHAR(32) NOT NULL,
  timeout_seconds INT NOT NULL DEFAULT 60,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  last_test_status VARCHAR(32),
  last_test_message VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_llm_provider UNIQUE (provider_name, base_url, model)
);

CREATE TABLE IF NOT EXISTS embedding_provider_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider_name VARCHAR(80) NOT NULL,
  mode VARCHAR(32) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  model VARCHAR(120) NOT NULL,
  api_key_encoded LONGTEXT NOT NULL,
  api_key_masked VARCHAR(32) NOT NULL,
  timeout_seconds INT NOT NULL DEFAULT 30,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  last_test_status VARCHAR(32),
  last_test_message VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_embedding_provider UNIQUE (mode, base_url, model)
);

CREATE TABLE IF NOT EXISTS rerank_provider_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider_name VARCHAR(80) NOT NULL,
  mode VARCHAR(32) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  endpoint VARCHAR(120) NOT NULL,
  model VARCHAR(120) NOT NULL,
  api_key_encoded LONGTEXT NOT NULL,
  api_key_masked VARCHAR(32) NOT NULL,
  timeout_seconds INT NOT NULL DEFAULT 15,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  last_test_status VARCHAR(32),
  last_test_message VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_rerank_provider UNIQUE (mode, base_url, endpoint, model)
);

CREATE TABLE IF NOT EXISTS upload_session (
  id VARCHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  file_md5 CHAR(32) NOT NULL,
  part_size BIGINT NOT NULL,
  total_parts INT NOT NULL,
  uploaded_bytes BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_upload_user_md5_status (user_id, file_md5, status),
  KEY idx_upload_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS upload_part (
  session_id VARCHAR(36) NOT NULL,
  part_number INT NOT NULL,
  size_bytes BIGINT NOT NULL,
  part_md5 CHAR(32) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, part_number),
  KEY idx_upload_part_session (session_id)
);

CREATE TABLE IF NOT EXISTS term_glossary_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_pattern VARCHAR(255) NOT NULL,
  replacement VARCHAR(255) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_term_glossary_entry UNIQUE (source_pattern, replacement),
  KEY idx_term_glossary_enabled (enabled, updated_at)
);
