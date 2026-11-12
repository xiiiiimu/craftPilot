-- R2 feedback attribution, evidence, embeddings, hybrid retrieval, and vision compare.

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE attribution_status (
    code varchar(30) PRIMARY KEY
);
INSERT INTO attribution_status (code) VALUES
    ('ATTRIBUTED'), ('UNATTRIBUTED'), ('AMBIGUOUS'), ('OUT_OF_WINDOW'), ('INVALIDATED');

CREATE TABLE message_attribution (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    message_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    revision_id uuid,
    status varchar(30) NOT NULL REFERENCES attribution_status(code),
    reason_code varchar(60) NOT NULL,
    source_quote varchar(4000),
    version bigint NOT NULL DEFAULT 0,
    extractor_version varchar(40) NOT NULL DEFAULT 'r2-attr-1',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, message_id, survey_id),
    FOREIGN KEY (merchant_id, message_id) REFERENCES group_message(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    CHECK (
        (status = 'ATTRIBUTED' AND revision_id IS NOT NULL)
        OR (status <> 'ATTRIBUTED')
    )
);

CREATE TABLE opinion_polarity (
    code varchar(20) PRIMARY KEY
);
INSERT INTO opinion_polarity (code) VALUES ('POSITIVE'), ('NEGATIVE'), ('NEUTRAL');

CREATE TABLE feedback_opinion (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    message_id uuid NOT NULL,
    attribution_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    author_id uuid NOT NULL,
    polarity varchar(20) NOT NULL REFERENCES opinion_polarity(code),
    attribute_key varchar(80) NOT NULL,
    canonical_text varchar(500) NOT NULL,
    quote_text varchar(2000) NOT NULL,
    confidence numeric(5, 4) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    extractor_version varchar(40) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, message_id, attribute_key, polarity, canonical_text),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, message_id) REFERENCES group_message(merchant_id, id),
    FOREIGN KEY (merchant_id, attribution_id) REFERENCES message_attribution(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, author_id) REFERENCES merchant_member(merchant_id, principal_id),
    CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'INVALIDATED'))
);

CREATE TABLE feedback_issue (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    style_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    issue_type varchar(80) NOT NULL,
    polarity varchar(20) NOT NULL REFERENCES opinion_polarity(code),
    title varchar(240) NOT NULL,
    independent_authors int NOT NULL DEFAULT 0 CHECK (independent_authors >= 0),
    status varchar(30) NOT NULL DEFAULT 'OPEN',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, revision_id, issue_type, polarity),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, style_id) REFERENCES candidate_style(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    CHECK (status IN ('OPEN', 'NEEDS_REVIEW', 'CLOSED'))
);

CREATE TABLE issue_opinion (
    merchant_id uuid NOT NULL,
    issue_id uuid NOT NULL,
    opinion_id uuid NOT NULL,
    linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, issue_id, opinion_id),
    FOREIGN KEY (merchant_id, issue_id) REFERENCES feedback_issue(merchant_id, id),
    FOREIGN KEY (merchant_id, opinion_id) REFERENCES feedback_opinion(merchant_id, id)
);

CREATE TABLE feedback_attribution_event (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    feedback_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    old_revision_id uuid,
    new_revision_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    version bigint NOT NULL,
    event_key uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, event_key),
    FOREIGN KEY (merchant_id, feedback_id) REFERENCES message_attribution(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, old_revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, new_revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, actor_id) REFERENCES merchant_member(merchant_id, principal_id)
);

CREATE TABLE evidence_grade (
    code varchar(10) PRIMARY KEY
);
INSERT INTO evidence_grade (code) VALUES ('A'), ('B'), ('C');

CREATE TABLE evidence_visibility (
    code varchar(20) PRIMARY KEY
);
INSERT INTO evidence_visibility (code) VALUES ('ACTIVE'), ('REVOKED'), ('FROZEN');

CREATE TABLE evidence_ref (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    source_type varchar(40) NOT NULL,
    source_id uuid NOT NULL,
    uri varchar(512),
    captured_at timestamptz NOT NULL,
    grade varchar(10) NOT NULL REFERENCES evidence_grade(code),
    checksum varchar(64) NOT NULL,
    visibility varchar(20) NOT NULL REFERENCES evidence_visibility(code),
    seq bigint NOT NULL CHECK (seq > 0),
    summary varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, seq),
    UNIQUE (merchant_id, survey_id, source_type, source_id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id)
);

CREATE TABLE evidence_chunk (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    chunk_ordinal int NOT NULL CHECK (chunk_ordinal >= 0),
    doc_version varchar(40) NOT NULL,
    content_text varchar(4000) NOT NULL,
    content_norm varchar(4000) NOT NULL,
    material_code varchar(80),
    embedding_model varchar(80),
    embedding_dim int,
    embedding_hash varchar(64),
    embedding public.vector(1536),
    visibility varchar(20) NOT NULL REFERENCES evidence_visibility(code),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, evidence_id, chunk_ordinal),
    FOREIGN KEY (merchant_id, evidence_id) REFERENCES evidence_ref(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    CHECK (
        (embedding IS NULL AND embedding_model IS NULL AND embedding_dim IS NULL AND embedding_hash IS NULL)
        OR (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_dim = 1536 AND embedding_hash IS NOT NULL)
    )
);

CREATE INDEX evidence_chunk_lexical_idx
    ON evidence_chunk (merchant_id, survey_id, revision_id, visibility);

CREATE INDEX evidence_chunk_material_idx
    ON evidence_chunk (merchant_id, survey_id, material_code)
    WHERE material_code IS NOT NULL;

CREATE TABLE model_call_attempt (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    provider varchar(40) NOT NULL,
    model_name varchar(120) NOT NULL,
    purpose varchar(40) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(30) NOT NULL,
    http_status int,
    provider_request_id varchar(120),
    input_tokens int,
    output_tokens int,
    estimated_cost_usd numeric(12, 6),
    error_code varchar(60),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'RATE_LIMITED'))
);

CREATE TABLE vision_comparison (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid,
    left_revision_id uuid NOT NULL,
    right_revision_id uuid NOT NULL,
    left_asset_id uuid,
    right_asset_id uuid,
    model_name varchar(120) NOT NULL,
    status varchar(30) NOT NULL,
    visible_differences jsonb,
    uncertainty varchar(400),
    evidence_id uuid,
    model_call_id uuid,
    gap_code varchar(60),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, left_revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, right_revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, left_asset_id) REFERENCES asset(merchant_id, id),
    FOREIGN KEY (merchant_id, right_asset_id) REFERENCES asset(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, evidence_id) REFERENCES evidence_ref(merchant_id, id),
    FOREIGN KEY (merchant_id, model_call_id) REFERENCES model_call_attempt(merchant_id, id),
    CHECK (status IN ('SUCCEEDED', 'GAP', 'FAILED')),
    CHECK (left_revision_id <> right_revision_id)
);

CREATE TABLE retrieval_execution (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    query_text varchar(1000) NOT NULL,
    retrieval_mode varchar(40) NOT NULL,
    profile_version varchar(40) NOT NULL,
    top_k int NOT NULL,
    result_json jsonb NOT NULL,
    evidence_watermark bigint,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id)
);
