ALTER TABLE link
    ADD COLUMN normalized_url VARCHAR(4096) NULL AFTER original_url,
    ADD COLUMN url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NULL AFTER normalized_url;
