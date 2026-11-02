create table asset
(
    id              bigint          not null auto_increment,
    link_id         bigint          not null,
    capture_job_id  bigint          not null,
    format          varchar(16)     not null,
    storage_path    varchar(1024)   not null,
    mime_type       varchar(127)    not null,
    file_size_bytes bigint unsigned not null,
    sha256          char(64)        not null,
    created_at      datetime(3)     not null,
    primary key (id),
    constraint ck_asset_format check (format in ('HTML', 'PDF', 'SCREENSHOT')),
    constraint ck_asset_sha256 check (sha256 REGEXP '^[0-9a-f]{64}$'),
    key idx_asset_link_created (link_id, created_at DESC),
    constraint fk_asset_link foreign key (link_id) references link (id) on delete cascade,
    constraint fk_asset_job foreign key (capture_job_id) references capture_job (id) on delete cascade
) engine = innodb
  default char set = utf8mb4
  collate = utf8mb4_0900_ai_ci
    comment = '快照资产元数据';