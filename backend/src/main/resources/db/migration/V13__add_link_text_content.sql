-- M4.3 stores extracted plain text for later full-text indexing.
ALTER TABLE link
    ADD COLUMN text_content MEDIUMTEXT NULL AFTER description;
