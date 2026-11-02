-- V10 may already have been applied; add the uniqueness rule without rewriting history.
ALTER TABLE asset
    ADD CONSTRAINT uk_asset_job_format UNIQUE (capture_job_id, format);
