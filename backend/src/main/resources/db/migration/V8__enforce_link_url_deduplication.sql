ALTER TABLE link
    MODIFY COLUMN normalized_url VARCHAR(4096) NOT NULL,
    MODIFY COLUMN url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT uk_link_user_url UNIQUE (user_id, url_hash);
