-- R4: Skill snapshots, supervisor handoff/join, MCP connectors, HITL, merchant decision, memory

-- Multi-agent turns are scoped per task (parallel workers share a run).
ALTER TABLE agent_turn DROP CONSTRAINT IF EXISTS agent_turn_merchant_id_run_id_turn_no_key;
ALTER TABLE agent_turn ADD CONSTRAINT agent_turn_run_task_turn_uq UNIQUE (merchant_id, run_id, task_id, turn_no);

ALTER TABLE approval
    ADD COLUMN IF NOT EXISTS task_id uuid,
    ADD COLUMN IF NOT EXISTS checkpoint_head bigint,
    ADD COLUMN IF NOT EXISTS kind varchar(40) NOT NULL DEFAULT 'GENERIC',
    ADD COLUMN IF NOT EXISTS operation_digest varchar(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS operation_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS requested_by uuid,
    ADD COLUMN IF NOT EXISTS expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS decision_actor uuid,
    ADD COLUMN IF NOT EXISTS decided_at timestamptz,
    ADD COLUMN IF NOT EXISTS decision_reason varchar(400),
    ADD COLUMN IF NOT EXISTS consumed_at timestamptz,
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE resume_command
    ADD COLUMN IF NOT EXISTS expected_version bigint,
    ADD COLUMN IF NOT EXISTS expected_checkpoint_head bigint,
    ADD COLUMN IF NOT EXISTS approval_id uuid,
    ADD COLUMN IF NOT EXISTS applied_checkpoint_id uuid,
    ADD COLUMN IF NOT EXISTS actor_id uuid,
    ADD COLUMN IF NOT EXISTS intent varchar(80) NOT NULL DEFAULT 'RESUME',
    ADD COLUMN IF NOT EXISTS applied_at timestamptz;

CREATE TABLE skill_release (
    id varchar(80) NOT NULL,
    version varchar(40) NOT NULL,
    schema_version varchar(40) NOT NULL,
    content_hash varchar(128) NOT NULL,
    role_code varchar(40) NOT NULL,
    instructions text NOT NULL,
    input_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    output_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    tool_whitelist jsonb NOT NULL DEFAULT '[]'::jsonb,
    model_profile_id varchar(80) NOT NULL REFERENCES model_profile(id),
    status varchar(30) NOT NULL DEFAULT 'PUBLISHED',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    PRIMARY KEY (id, version),
    CHECK (status IN ('PUBLISHED', 'REVOKED'))
);

CREATE TABLE skill_snapshot (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    workflow_id varchar(80) NOT NULL,
    workflow_version varchar(40) NOT NULL,
    manifest_json jsonb NOT NULL,
    content_hash varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id)
);

CREATE TABLE agent_run_skill (
    merchant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    skill_id varchar(80) NOT NULL,
    skill_version varchar(40) NOT NULL,
    snapshot_id uuid NOT NULL,
    role_code varchar(40) NOT NULL,
    PRIMARY KEY (merchant_id, run_id, skill_id),
    FOREIGN KEY (merchant_id, snapshot_id) REFERENCES skill_snapshot(merchant_id, id),
    FOREIGN KEY (merchant_id, run_id) REFERENCES agent_run(merchant_id, id)
);

CREATE TABLE handoff (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    handoff_key varchar(160) NOT NULL,
    parent_task_id uuid NOT NULL,
    child_task_id uuid NOT NULL,
    target_role varchar(40) NOT NULL,
    skill_id varchar(80) NOT NULL,
    skill_version varchar(40) NOT NULL,
    objective text NOT NULL,
    evidence_scope_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    token_budget int NOT NULL,
    tool_budget int NOT NULL,
    deadline_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, handoff_key),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, parent_task_id) REFERENCES agent_task(merchant_id, id),
    FOREIGN KEY (merchant_id, child_task_id) REFERENCES agent_task(merchant_id, id),
    CHECK (status IN ('SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'))
);

CREATE TABLE task_join (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    parent_task_id uuid NOT NULL,
    join_key varchar(160) NOT NULL,
    policy varchar(40) NOT NULL DEFAULT 'ALL_REQUIRED',
    deadline_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    result_version bigint NOT NULL DEFAULT 0,
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, join_key),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, parent_task_id) REFERENCES agent_task(merchant_id, id),
    CHECK (policy IN ('ALL_REQUIRED', 'QUORUM', 'ANY_SUCCESS')),
    CHECK (status IN ('WAITING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'))
);

CREATE TABLE task_join_member (
    merchant_id uuid NOT NULL,
    join_id uuid NOT NULL,
    child_task_id uuid NOT NULL,
    handoff_id uuid,
    required boolean NOT NULL DEFAULT true,
    status varchar(30) NOT NULL DEFAULT 'PENDING',
    result_json jsonb,
    completed_at timestamptz,
    result_epoch bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (merchant_id, join_id, child_task_id),
    FOREIGN KEY (merchant_id, join_id) REFERENCES task_join(merchant_id, id),
    FOREIGN KEY (merchant_id, child_task_id) REFERENCES agent_task(merchant_id, id),
    CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED', 'EXPIRED'))
);

CREATE TABLE connector_config (
    merchant_id uuid NOT NULL,
    id varchar(80) NOT NULL,
    endpoint_url text NOT NULL,
    protocol_version varchar(40) NOT NULL DEFAULT '2026-07-28',
    credential_ref varchar(120),
    tool_whitelist jsonb NOT NULL DEFAULT '[]'::jsonb,
    catalog_version varchar(64),
    catalog_hash varchar(128),
    status varchar(30) NOT NULL DEFAULT 'ACTIVE',
    allow_loopback boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE connector_catalog (
    merchant_id uuid NOT NULL,
    connector_id varchar(80) NOT NULL,
    tool_name varchar(120) NOT NULL,
    input_schema_hash varchar(128) NOT NULL,
    input_schema jsonb NOT NULL,
    output_schema_hash varchar(128),
    output_schema jsonb,
    catalog_version varchar(64) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (merchant_id, connector_id, tool_name, catalog_version),
    FOREIGN KEY (merchant_id, connector_id) REFERENCES connector_config(merchant_id, id),
    CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE mcp_invocation (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    task_id uuid,
    connector_id varchar(80) NOT NULL,
    tool_name varchar(120) NOT NULL,
    request_hash varchar(128) NOT NULL,
    status varchar(30) NOT NULL,
    http_status int,
    error_code varchar(80),
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, connector_id) REFERENCES connector_config(merchant_id, id),
    CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'TIMEOUT'))
);

CREATE TABLE research_report (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    content_hash varchar(128) NOT NULL,
    content_json jsonb NOT NULL,
    evidence_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id)
);

CREATE TABLE merchant_decision (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    report_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    recommendation varchar(40) NOT NULL,
    status varchar(30) NOT NULL,
    report_hash varchar(128) NOT NULL,
    decided_by uuid,
    decision_choice varchar(40),
    decision_note varchar(400),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    decided_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, report_id, revision_id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, report_id) REFERENCES research_report(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'CANCELLED'))
);

CREATE TABLE memory_proposal (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid,
    run_id uuid,
    scope_type varchar(40) NOT NULL,
    scope_key varchar(120) NOT NULL,
    summary text NOT NULL,
    content_hash varchar(128) NOT NULL,
    evidence_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(30) NOT NULL,
    approval_id uuid,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    CHECK (scope_type IN ('MERCHANT', 'STYLE', 'CATEGORY', 'SURVEY')),
    CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'PUBLISHED', 'REVOKED'))
);

CREATE TABLE memory_entry (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    scope_type varchar(40) NOT NULL,
    scope_key varchar(120) NOT NULL,
    summary text NOT NULL,
    content_hash varchar(128) NOT NULL,
    evidence_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    version bigint NOT NULL DEFAULT 1,
    status varchar(30) NOT NULL,
    published_by uuid NOT NULL,
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    revoked_by uuid,
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, proposal_id) REFERENCES memory_proposal(merchant_id, id),
    CHECK (scope_type IN ('MERCHANT', 'STYLE', 'CATEGORY', 'SURVEY')),
    CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX memory_entry_scope_idx
    ON memory_entry (merchant_id, scope_type, scope_key)
    WHERE status = 'ACTIVE';

-- Seed R4 skills and supervisor workflow
INSERT INTO skill_release (id, version, schema_version, content_hash, role_code, instructions,
                           tool_whitelist, model_profile_id, status) VALUES
('supervisor-skill-r4', '1', 'r4-skill-1', 'sha256:supervisor-skill-r4-v1', 'SUPERVISOR',
 'You are CraftPilot supervisor. Plan handoffs to worker roles evidence_worker and material_worker. Return JSON with handoffs array of {role,objective,required}. Do not invent evidence.',
 '["evidence_search","evidence_verify","sample_get_snapshot","survey_get_stats"]'::jsonb,
 'chat-default-r3', 'PUBLISHED'),
('evidence-worker-skill-r4', '1', 'r4-skill-1', 'sha256:evidence-worker-skill-r4-v1', 'EVIDENCE_WORKER',
 'You are an evidence worker. Use evidence_search/evidence_verify only. Return JSON {summary,evidenceIds}.',
 '["evidence_search","evidence_verify"]'::jsonb,
 'chat-default-r3', 'PUBLISHED'),
('material-worker-skill-r4', '1', 'r4-skill-1', 'sha256:material-worker-skill-r4-v1', 'MATERIAL_WORKER',
 'You are a material worker. Use sample_get_snapshot and mcp_material_lookup when available. Return JSON {summary,evidenceIds,materialNotes}.',
 '["sample_get_snapshot","survey_get_stats","mcp_material_lookup"]'::jsonb,
 'chat-default-r3', 'PUBLISHED');

INSERT INTO workflow_release (id, version, schema_version, content_hash, graph_json) VALUES
('launch-supervise-r4', '1', 'r4-wf-1', 'sha256:launch-supervise-r4-v1',
 '{"nodes":[{"id":"plan","type":"supervisor"},{"id":"fanout","type":"handoff"},{"id":"join","type":"join"},{"id":"hitl","type":"approval"},{"id":"memory","type":"memory"},{"id":"publish","type":"publish"}],"edges":[{"from":"plan","to":"fanout"},{"from":"fanout","to":"join"},{"from":"join","to":"hitl"},{"from":"hitl","to":"memory"},{"from":"memory","to":"publish"}]}'::jsonb);

INSERT INTO agent_spec (id, version, schema_version, content_hash, instructions, max_turns, max_tool_calls) VALUES
('supervisor-agent-r4', '1', 'r4-as-1', 'sha256:supervisor-agent-r4-v1',
 'Supervisor orchestrates workers, joins results, requests HITL, and proposes memory. Never self-approve.',
 12, 24)
ON CONFLICT (id) DO NOTHING;

INSERT INTO tool_release (tool_name, version, schema_version, content_hash, input_schema, effect_class) VALUES
('mcp_material_lookup', '1', 'r4-tool-1', 'sha256:mcp_material_lookup-v1',
 '{"type":"object","required":["materialCode"],"properties":{"materialCode":{"type":"string","minLength":1,"maxLength":80},"connectorId":{"type":"string"}},"additionalProperties":false}'::jsonb,
 'READ_ONLY')
ON CONFLICT (tool_name) DO NOTHING;
