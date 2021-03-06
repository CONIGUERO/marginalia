DROP TABLE IF EXISTS REF_DICTIONARY;

CREATE TABLE IF NOT EXISTS REF_DICTIONARY(
    TYPE VARCHAR(16),
    WORD VARCHAR(255),
    DEFINITION VARCHAR(255)
)
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS REF_DICTIONARY_WORD ON REF_DICTIONARY (WORD);

CREATE TABLE IF NOT EXISTS REF_WIKI_TITLE(
    NAME VARCHAR(255),
    NAME_LOWER VARCHAR(255) GENERATED ALWAYS AS (LOWER(NAME)),
    REF_NAME VARCHAR(255)
)
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;


CREATE INDEX IF NOT EXISTS REF_WIKI_LOWER ON REF_WIKI_TITLE (NAME_LOWER);
CREATE INDEX IF NOT EXISTS REF_WIKI_NAME ON REF_WIKI_TITLE (NAME);