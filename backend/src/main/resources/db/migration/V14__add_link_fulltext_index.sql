-- M6.1: enable user-scoped natural-language search across link text fields.
ALTER TABLE link
    ADD FULLTEXT INDEX ft_link_content (title, description, text_content)
        WITH PARSER ngram;
