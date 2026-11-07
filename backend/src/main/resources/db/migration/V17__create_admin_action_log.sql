CREATE TABLE admin_action_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    admin_user_id BIGINT       NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    source_job_id BIGINT       NOT NULL,
    created_job_id BIGINT      NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_action_created (created_at DESC, id DESC),
    KEY idx_admin_action_job (source_job_id, created_job_id),
    CONSTRAINT ck_admin_action_type
        CHECK (action IN ('RETRY_CAPTURE_JOB')),
    CONSTRAINT fk_admin_action_user
        FOREIGN KEY (admin_user_id) REFERENCES app_user (id)
        ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '管理员操作审计日志';
