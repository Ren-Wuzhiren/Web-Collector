CREATE TABLE asset_cleanup_job
(
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    storage_path    VARCHAR(1024)   NOT NULL,
    storage_path_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3)     NOT NULL,
    locked_until    DATETIME(3)     NULL,
    last_error      VARCHAR(1000)   NULL,
    created_at      DATETIME(3)     NOT NULL,
    updated_at      DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_cleanup_path_hash (storage_path_hash),
    KEY idx_asset_cleanup_due (next_attempt_at, locked_until, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '待重试的本地资产文件清理任务';
