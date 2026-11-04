-- Giai đoạn 4C/4D: RAG + memory (pgvector)
CREATE TABLE knowledge_collection (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    sensitivity VARCHAR(20)  NOT NULL DEFAULT 'INTERNAL',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_document (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_id UUID NOT NULL REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    title         VARCHAR(300) NOT NULL,
    source        VARCHAR(500),
    content_hash  VARCHAR(64),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|INGESTING|READY|FAILED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_chunk (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    ordinal     INT  NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1536),
    tsv         tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED
);
CREATE INDEX idx_chunk_tsv ON knowledge_chunk USING gin(tsv);
-- Index vector tạo sau khi có dữ liệu: CREATE INDEX ... USING hnsw (embedding vector_cosine_ops);

CREATE TABLE memory_namespace (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name      VARCHAR(100) NOT NULL UNIQUE,
    agent_id  UUID REFERENCES agent(id)
);

CREATE TABLE memory_item (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id  UUID NOT NULL REFERENCES memory_namespace(id) ON DELETE CASCADE,
    content       TEXT NOT NULL,
    embedding     vector(1536),
    provenance    JSONB NOT NULL,                 -- nguồn: task/message nào sinh ra
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW', -- PENDING_REVIEW|ACTIVE|REJECTED|FORGOTTEN
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
