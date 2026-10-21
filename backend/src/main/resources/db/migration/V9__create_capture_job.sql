-- [软件工程] Flyway 约定：V<版本>__<描述>.sql，版本号与描述之间是两个下划线。
-- [排错] V7 是 Java 迁移（V7__BackfillLinkUrlDeduplication），所以这里接 V9 而不是 V8。
-- [排错] 已执行过的迁移永不修改，结构变化一律新增更高版本文件。
CREATE TABLE capture_job
(
    id              BIGINT           NOT NULL AUTO_INCREMENT,
    link_id         BIGINT           NOT NULL,
    requested_by    BIGINT           NOT NULL,
    -- [业务] 四态先定死：本轮只写 PENDING，其余三态留给 M3 的状态机迁移。
    status          ENUM ('PENDING','RUNNING','SUCCEEDED','FAILED') NOT NULL,
    -- [业务] attempt_count 本轮恒为 0；max_attempts 是 M3 指数退避读的上限。
    attempt_count   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    max_attempts    TINYINT UNSIGNED NOT NULL DEFAULT 3,
    -- [排错] 不能留 NULL：M3 的领取条件是 next_attempt_at <= now()，
    -- NULL 参与比较恒为 unknown，任务会永远领不到。
    next_attempt_at DATETIME(3)      NOT NULL,
    locked_by       VARCHAR(100)     NULL,
    locked_until    DATETIME(3)      NULL,
    started_at      DATETIME(3)      NULL,
    finished_at     DATETIME(3)      NULL,
    error_code      VARCHAR(64)      NULL,
    error_message   VARCHAR(1000)    NULL,
    created_at      DATETIME(3)      NOT NULL,
    updated_at      DATETIME(3)      NOT NULL,
    PRIMARY KEY (id),
    -- [408] 三列顺序对齐 M3 的 WHERE 与 ORDER BY：status 等值 → next_attempt_at 范围
    -- → id 排序，走一个索引就能出结果，不用额外 filesort。
    KEY idx_job_claim (status, next_attempt_at, id),
    KEY idx_job_recovery (status, locked_until),
    -- [业务] 同一链接将来可以有多条任务（重采集），所以只建普通索引，绝不加唯一约束。
    KEY idx_job_link_created (link_id, created_at DESC),
    -- [业务] 删链接时任务跟着走；删用户时链接先级联，任务再随链接级联。
    CONSTRAINT fk_capture_job_link FOREIGN KEY (link_id) REFERENCES link (id) ON DELETE CASCADE,
    CONSTRAINT fk_capture_job_user FOREIGN KEY (requested_by) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '抓取任务表';