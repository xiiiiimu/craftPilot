-- R3 Agent Runtime: trigger/ticket/assignment/attempt, run/task, tools, budget, checkpoint.

CREATE TABLE source_event (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    source_type varchar(40) NOT NULL,
    source_key varchar(160) NOT NULL,
    revision_id uuid,
    occurred_at timestamptz NOT NULL,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, source_type, source_key),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id)
);

CREATE TABLE trigger_disposition (
    code varchar(40) PRIMARY KEY
);
INSERT INTO trigger_disposition (code) VALUES
    ('ACCEPTED'), ('DEDUPED'), ('EXPIRED'), ('INSUFFICIENT_EVIDENCE'), ('NO_SUBSCRIPTION'), ('REJECTED');

CREATE TABLE trigger_evaluation (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    rule_version varchar(40) NOT NULL,
    disposition varchar(40) NOT NULL REFERENCES trigger_disposition(code),
    ticket_id uuid,
    reason_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, source_event_id) REFERENCES source_event(merchant_id, id)
);

CREATE TABLE ticket_status (
    code varchar(30) PRIMARY KEY
);
INSERT INTO ticket_status (code) VALUES
    ('CREATED'), ('QUEUED'), ('RUNNING'), ('WAITING_APPROVAL'),
    ('COMPLETED'), ('FAILED'), ('CANCELLED'), ('EXPIRED');

CREATE TABLE ticket (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    objective varchar(500) NOT NULL,
    status varchar(30) NOT NULL REFERENCES ticket_status(code),
    source_event_id uuid,
    deadline_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    terminal_reason varchar(120),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, source_event_id) REFERENCES source_event(merchant_id, id),
    FOREIGN KEY (merchant_id, created_by) REFERENCES merchant_member(merchant_id, principal_id)
);

CREATE TABLE ticket_revision (
    merchant_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    PRIMARY KEY (merchant_id, ticket_id, revision_id),
    FOREIGN KEY (merchant_id, survey_id, ticket_id) REFERENCES ticket(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, survey_id, revision_id) REFERENCES survey_revision(merchant_id, survey_id, revision_id)
);

CREATE TABLE assignment_status (
    code varchar(30) PRIMARY KEY
);
INSERT INTO assignment_status (code) VALUES
    ('READY'), ('CLAIMED'), ('DONE'), ('FAILED'), ('CANCELLED'), ('EXPIRED');

CREATE TABLE assignment (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    capability_profile varchar(80) NOT NULL DEFAULT 'research-r3',
    status varchar(30) NOT NULL REFERENCES assignment_status(code),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claimed_at timestamptz,
    finished_at timestamptz,
    terminal_reason varchar(120),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, ticket_id, id),
    FOREIGN KEY (merchant_id, survey_id, ticket_id) REFERENCES ticket(merchant_id, survey_id, id)
);

CREATE UNIQUE INDEX assignment_one_active_idx
    ON assignment (merchant_id, ticket_id)
    WHERE status IN ('READY', 'CLAIMED');

CREATE TABLE attempt_status (
    code varchar(30) PRIMARY KEY
);
INSERT INTO attempt_status (code) VALUES
    ('PENDING'), ('EXECUTING'), ('SUCCEEDED'), ('FAILED'), ('CANCELLED'), ('EXPIRED'), ('LOST');

CREATE TABLE agent_run_attempt (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    attempt_no int NOT NULL CHECK (attempt_no >= 1),
    status varchar(30) NOT NULL REFERENCES attempt_status(code),
    retry_reason varchar(120),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, ticket_id, attempt_no),
    UNIQUE (merchant_id, assignment_id),
    FOREIGN KEY (merchant_id, survey_id, ticket_id) REFERENCES ticket(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, survey_id, ticket_id, assignment_id)
        REFERENCES assignment(merchant_id, survey_id, ticket_id, id)
);

CREATE TABLE workflow_release (
    id varchar(80) PRIMARY KEY,
    version varchar(40) NOT NULL,
    schema_version varchar(20) NOT NULL,
    content_hash varchar(64) NOT NULL,
    graph_json jsonb NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE agent_spec (
    id varchar(80) PRIMARY KEY,
    version varchar(40) NOT NULL,
    schema_version varchar(20) NOT NULL,
    content_hash varchar(64) NOT NULL,
    instructions text NOT NULL,
    max_turns int NOT NULL DEFAULT 8,
    max_tool_calls int NOT NULL DEFAULT 16,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE tool_release (
    tool_name varchar(80) PRIMARY KEY,
    version varchar(40) NOT NULL,
    schema_version varchar(20) NOT NULL,
    content_hash varchar(64) NOT NULL,
    input_schema jsonb NOT NULL,
    effect_class varchar(40) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (effect_class IN ('READ_ONLY', 'SAME_DB_WRITE', 'EXTERNAL_WRITE'))
);

CREATE TABLE model_profile (
    id varchar(80) PRIMARY KEY,
    version varchar(40) NOT NULL,
    provider varchar(40) NOT NULL,
    model_name varchar(120) NOT NULL,
    content_hash varchar(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE run_status (
    code varchar(30) PRIMARY KEY
);
INSERT INTO run_status (code) VALUES
    ('QUEUED'), ('RUNNING'), ('WAITING_APPROVAL'), ('PAUSED'), ('RECOVERING'),
    ('CANCEL_REQUESTED'), ('SUCCEEDED'), ('FAILED'), ('CANCELLED'), ('EXPIRED');

CREATE TABLE agent_run (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    workflow_id varchar(80) NOT NULL REFERENCES workflow_release(id),
    workflow_version varchar(40) NOT NULL,
    agent_spec_id varchar(80) NOT NULL REFERENCES agent_spec(id),
    model_profile_id varchar(80) NOT NULL REFERENCES model_profile(id),
    status varchar(30) NOT NULL REFERENCES run_status(code),
    version bigint NOT NULL DEFAULT 0,
    checkpoint_head bigint NOT NULL DEFAULT 0,
    current_node varchar(60) NOT NULL DEFAULT 'snapshot',
    lease_owner varchar(80),
    lease_epoch bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    started_at timestamptz,
    execution_deadline timestamptz NOT NULL,
    max_turns int NOT NULL DEFAULT 8,
    max_tool_calls int NOT NULL DEFAULT 16,
    token_budget int NOT NULL DEFAULT 8000,
    tokens_used int NOT NULL DEFAULT 0,
    tool_calls_used int NOT NULL DEFAULT 0,
    turns_used int NOT NULL DEFAULT 0,
    cost_budget_usd numeric(12, 6) NOT NULL DEFAULT 1.000000,
    cost_used_usd numeric(12, 6) NOT NULL DEFAULT 0,
    final_output_json jsonb,
    terminal_reason varchar(160),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, id),
    UNIQUE (merchant_id, attempt_id),
    FOREIGN KEY (merchant_id, survey_id, ticket_id) REFERENCES ticket(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, attempt_id) REFERENCES agent_run_attempt(merchant_id, id)
);

CREATE UNIQUE INDEX agent_run_one_active_per_ticket_idx
    ON agent_run (merchant_id, ticket_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'PAUSED', 'RECOVERING', 'CANCEL_REQUESTED');

CREATE INDEX agent_run_claim_idx
    ON agent_run (status, lease_until, created_at)
    WHERE status IN ('QUEUED', 'RUNNING', 'RECOVERING', 'CANCEL_REQUESTED');

CREATE TABLE task_status (
    code varchar(30) PRIMARY KEY
);
INSERT INTO task_status (code) VALUES
    ('READY'), ('RUNNING'), ('BLOCKED'), ('SUCCEEDED'), ('FAILED'), ('CANCELLED'), ('SKIPPED'), ('EXPIRED');

CREATE TABLE agent_task (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    parent_task_id uuid,
    logical_key varchar(120) NOT NULL,
    node_id varchar(60) NOT NULL,
    agent_spec_id varchar(80) REFERENCES agent_spec(id),
    status varchar(30) NOT NULL REFERENCES task_status(code),
    result_json jsonb,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, logical_key),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, parent_task_id) REFERENCES agent_task(merchant_id, id)
);

CREATE TABLE agent_turn (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    task_id uuid NOT NULL,
    turn_no int NOT NULL CHECK (turn_no >= 1),
    status varchar(30) NOT NULL,
    context_id uuid,
    model_call_id uuid,
    request_summary varchar(500),
    response_summary varchar(1000),
    tool_batch_json jsonb,
    input_tokens int,
    output_tokens int,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, turn_no),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, task_id) REFERENCES agent_task(merchant_id, id),
    FOREIGN KEY (merchant_id, model_call_id) REFERENCES model_call_attempt(merchant_id, id),
    CHECK (status IN ('REQUESTED', 'RESPONSE_COMMITTED', 'FAILED', 'ABANDONED'))
);

CREATE TABLE context_snapshot (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    task_id uuid,
    instruction_version varchar(40) NOT NULL,
    messages_json jsonb NOT NULL,
    tool_schemas_json jsonb NOT NULL,
    evidence_refs_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    token_estimate int NOT NULL DEFAULT 0,
    omitted_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id)
);

CREATE TABLE checkpoint (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq >= 1),
    parent_seq bigint,
    transition_key varchar(160) NOT NULL,
    schema_version varchar(20) NOT NULL DEFAULT 'r3-cp-1',
    node_id varchar(60) NOT NULL,
    status varchar(30) NOT NULL,
    state_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, seq),
    UNIQUE (merchant_id, run_id, transition_key),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id)
);

CREATE TABLE tool_invocation (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    task_id uuid NOT NULL,
    turn_id uuid,
    ordinal int NOT NULL CHECK (ordinal >= 0),
    tool_name varchar(80) NOT NULL,
    tool_version varchar(40) NOT NULL,
    arguments_json jsonb NOT NULL,
    arguments_hash varchar(64) NOT NULL,
    operation_key varchar(160) NOT NULL,
    effect_class varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    lease_epoch bigint NOT NULL,
    result_json jsonb,
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, turn_id, ordinal),
    UNIQUE (merchant_id, operation_key),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, task_id) REFERENCES agent_task(merchant_id, id),
    FOREIGN KEY (merchant_id, turn_id) REFERENCES agent_turn(merchant_id, id),
    CHECK (status IN ('PREPARED', 'DISPATCHING', 'SUCCEEDED', 'FAILED_FINAL', 'FAILED_RETRYABLE',
                      'UNKNOWN', 'DENIED', 'CANCELLED')),
    CHECK (effect_class IN ('READ_ONLY', 'SAME_DB_WRITE', 'EXTERNAL_WRITE'))
);

CREATE TABLE tool_dispatch_attempt (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    invocation_id uuid NOT NULL,
    attempt_no int NOT NULL CHECK (attempt_no >= 1),
    lease_epoch bigint NOT NULL,
    status varchar(40) NOT NULL,
    remote_request_id varchar(120),
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, invocation_id, attempt_no),
    FOREIGN KEY (merchant_id, invocation_id) REFERENCES tool_invocation(merchant_id, id),
    CHECK (status IN ('SENT', 'SUCCEEDED', 'FAILED', 'TIMEOUT'))
);

CREATE TABLE effect_receipt (
    merchant_id uuid NOT NULL,
    operation_key varchar(160) NOT NULL,
    invocation_id uuid NOT NULL,
    result_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, operation_key),
    FOREIGN KEY (merchant_id, invocation_id) REFERENCES tool_invocation(merchant_id, id)
);

CREATE TABLE budget_reservation (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    purpose varchar(40) NOT NULL,
    tokens_reserved int NOT NULL DEFAULT 0,
    cost_reserved_usd numeric(12, 6) NOT NULL DEFAULT 0,
    status varchar(30) NOT NULL,
    model_call_id uuid,
    tool_invocation_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    settled_at timestamptz,
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    CHECK (status IN ('HELD', 'SETTLED', 'RELEASED', 'UNKNOWN')),
    CHECK (purpose IN ('MODEL', 'TOOL', 'RUN'))
);

CREATE TABLE usage_ledger (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    kind varchar(40) NOT NULL,
    provider_request_id varchar(120),
    tokens_in int,
    tokens_out int,
    cost_usd numeric(12, 6),
    accuracy varchar(20) NOT NULL,
    model_call_id uuid,
    tool_invocation_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    CHECK (accuracy IN ('EXACT', 'ESTIMATED', 'UNKNOWN')),
    CHECK (kind IN ('MODEL', 'TOOL', 'EMBEDDING', 'VISION'))
);

CREATE UNIQUE INDEX usage_ledger_provider_dedup_idx
    ON usage_ledger (merchant_id, kind, provider_request_id)
    WHERE provider_request_id IS NOT NULL;

CREATE TABLE runtime_event (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq >= 1),
    event_type varchar(80) NOT NULL,
    task_id uuid,
    trace_id varchar(64),
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, seq),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id)
);

-- R4 extension stubs (no R3 write path uses these)
CREATE TABLE approval (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'REVOKED', 'CONSUMED'))
);

CREATE TABLE resume_command (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    run_id uuid NOT NULL,
    command_key varchar(160) NOT NULL,
    status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, run_id, command_key),
    FOREIGN KEY (merchant_id, survey_id, run_id) REFERENCES agent_run(merchant_id, survey_id, id),
    CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'EXPIRED'))
);

-- Seed workflow / agent / tools / model profile
INSERT INTO workflow_release (id, version, schema_version, content_hash, graph_json) VALUES
('launch-research-r3', '1', 'r3-wf-1', 'sha256:launch-research-r3-v1',
 '{"nodes":[{"id":"snapshot","type":"read"},{"id":"research","type":"agent"},{"id":"validate","type":"validate"},{"id":"publish","type":"publish"}],"edges":[{"from":"snapshot","to":"research"},{"from":"research","to":"validate"},{"from":"validate","to":"publish"}]}'::jsonb);

INSERT INTO agent_spec (id, version, schema_version, content_hash, instructions, max_turns, max_tool_calls) VALUES
('research-agent-r3', '1', 'r3-as-1', 'sha256:research-agent-r3-v1',
 'You are CraftPilot research agent. Use only provided tools. Cite real evidence IDs returned by tools. Never invent evidence IDs or elevate permissions. When enough evidence exists, return a final JSON object with keys recommendation (PRODUCE|REVISE|TRIAL|HOLD), summary, evidenceIds (array of UUID strings).',
 8, 16);

INSERT INTO model_profile (id, version, provider, model_name, content_hash) VALUES
('chat-default-r3', '1', 'openai-compatible', 'gpt-4o-mini', 'sha256:chat-default-r3-v1');

INSERT INTO tool_release (tool_name, version, schema_version, content_hash, input_schema, effect_class) VALUES
('sample_get_snapshot', '1', 'r3-tool-1', 'sha256:sample_get_snapshot-v1',
 '{"type":"object","required":["revisionId"],"properties":{"revisionId":{"type":"string","description":"UUID"}},"additionalProperties":false}'::jsonb,
 'READ_ONLY'),
('survey_get_stats', '1', 'r3-tool-1', 'sha256:survey_get_stats-v1',
 '{"type":"object","required":["revisionId"],"properties":{"revisionId":{"type":"string","description":"UUID"}},"additionalProperties":false}'::jsonb,
 'READ_ONLY'),
('evidence_search', '1', 'r3-tool-1', 'sha256:evidence_search-v1',
 '{"type":"object","required":["revisionId","query"],"properties":{"revisionId":{"type":"string","description":"UUID"},"query":{"type":"string","minLength":1,"maxLength":500},"topK":{"type":"integer","minimum":1,"maximum":20}},"additionalProperties":false}'::jsonb,
 'READ_ONLY'),
('evidence_verify', '1', 'r3-tool-1', 'sha256:evidence_verify-v1',
 '{"type":"object","required":["evidenceId"],"properties":{"evidenceId":{"type":"string","description":"UUID"}},"additionalProperties":false}'::jsonb,
 'READ_ONLY'),
('vision_get_comparison', '1', 'r3-tool-1', 'sha256:vision_get_comparison-v1',
 '{"type":"object","required":["comparisonId"],"properties":{"comparisonId":{"type":"string","description":"UUID"}},"additionalProperties":false}'::jsonb,
 'READ_ONLY');
