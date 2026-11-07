-- M6.2: provide stable user-scoped and collection-scoped keyset access paths.
CREATE INDEX idx_link_user_id_desc
    ON link (user_id, id DESC);

CREATE INDEX idx_link_user_collection_id_desc
    ON link (user_id, collection_id, id DESC);