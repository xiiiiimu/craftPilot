-- R1 business domain, messaging, survey windows, and reliable outbox.

CREATE TABLE craft_group (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    next_message_seq bigint NOT NULL DEFAULT 0 CHECK (next_message_seq >= 0),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id) REFERENCES merchant(id)
);

CREATE TABLE group_role (
    code varchar(20) PRIMARY KEY
);
INSERT INTO group_role (code) VALUES ('MEMBER'), ('ADMIN');

CREATE TABLE group_member (
    merchant_id uuid NOT NULL,
    group_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    role_code varchar(20) NOT NULL REFERENCES group_role(code),
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, group_id, principal_id),
    FOREIGN KEY (merchant_id, group_id) REFERENCES craft_group(merchant_id, id),
    FOREIGN KEY (merchant_id, principal_id) REFERENCES merchant_member(merchant_id, principal_id)
);

CREATE INDEX group_member_active_idx
    ON group_member (merchant_id, group_id, principal_id)
    WHERE revoked_at IS NULL;

CREATE TABLE launch_campaign (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    description varchar(2000),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    FOREIGN KEY (merchant_id, created_by) REFERENCES merchant_member(merchant_id, principal_id)
);

CREATE TABLE candidate_style (
    merchant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    id uuid NOT NULL,
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    category varchar(80) NOT NULL CHECK (length(btrim(category)) > 0),
    next_revision_no bigint NOT NULL DEFAULT 0 CHECK (next_revision_no >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, campaign_id, id),
    FOREIGN KEY (merchant_id, campaign_id) REFERENCES launch_campaign(merchant_id, id)
);

CREATE TABLE asset_status (
    code varchar(20) PRIMARY KEY
);
INSERT INTO asset_status (code) VALUES
    ('STAGED'), ('AVAILABLE'), ('FAILED'), ('REVOKED'), ('DELETE_PENDING'), ('DELETED');

CREATE TABLE asset (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    owner_principal_id uuid NOT NULL,
    kind varchar(40) NOT NULL CHECK (length(btrim(kind)) > 0),
    content_type varchar(120) NOT NULL,
    object_key varchar(512) NOT NULL,
    object_version varchar(128),
    content_sha256 varchar(64),
    byte_size bigint CHECK (byte_size IS NULL OR byte_size >= 0),
    status varchar(20) NOT NULL REFERENCES asset_status(code),
    failure_reason varchar(400),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, object_key),
    FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    FOREIGN KEY (merchant_id, owner_principal_id) REFERENCES merchant_member(merchant_id, principal_id)
);

CREATE TABLE sample_revision (
    merchant_id uuid NOT NULL,
    style_id uuid NOT NULL,
    id uuid NOT NULL,
    revision_no bigint NOT NULL CHECK (revision_no > 0),
    parent_revision_id uuid,
    notes varchar(2000),
    snapshot_hash varchar(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, style_id, revision_no),
    UNIQUE (merchant_id, style_id, id),
    FOREIGN KEY (merchant_id, style_id) REFERENCES candidate_style(merchant_id, id),
    FOREIGN KEY (merchant_id, created_by) REFERENCES merchant_member(merchant_id, principal_id),
    FOREIGN KEY (merchant_id, style_id, parent_revision_id)
        REFERENCES sample_revision(merchant_id, style_id, id)
);

CREATE TABLE bom_item (
    merchant_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    line_no int NOT NULL CHECK (line_no > 0),
    material_code varchar(80) NOT NULL CHECK (length(btrim(material_code)) > 0),
    material_name varchar(160) NOT NULL CHECK (length(btrim(material_name)) > 0),
    quantity numeric(18, 6) NOT NULL CHECK (quantity > 0),
    unit varchar(20) NOT NULL CHECK (length(btrim(unit)) > 0),
    unit_cost numeric(18, 6),
    currency char(3),
    cost_gap boolean NOT NULL DEFAULT false,
    PRIMARY KEY (merchant_id, revision_id, line_no),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    CHECK ((unit_cost IS NULL AND currency IS NULL) OR (unit_cost IS NOT NULL AND currency IS NOT NULL)),
    CHECK (NOT cost_gap OR unit_cost IS NULL)
);

CREATE TABLE revision_asset (
    merchant_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    PRIMARY KEY (merchant_id, revision_id, asset_id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, asset_id) REFERENCES asset(merchant_id, id)
);

CREATE TABLE survey_status (
    code varchar(20) PRIMARY KEY
);
INSERT INTO survey_status (code) VALUES ('CREATED'), ('ACTIVE'), ('CLOSING'), ('CLOSED');

CREATE TABLE survey_session (
    merchant_id uuid NOT NULL,
    group_id uuid NOT NULL,
    id uuid NOT NULL,
    status varchar(20) NOT NULL REFERENCES survey_status(code),
    starts_at timestamptz,
    planned_ends_at timestamptz,
    intake_until timestamptz,
    closing_deadline_at timestamptz,
    closing_started_at timestamptz,
    closed_at timestamptz,
    evidence_seq bigint NOT NULL DEFAULT 0 CHECK (evidence_seq >= 0),
    evidence_watermark bigint,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, group_id, id),
    FOREIGN KEY (merchant_id, group_id) REFERENCES craft_group(merchant_id, id),
    FOREIGN KEY (merchant_id, created_by) REFERENCES merchant_member(merchant_id, principal_id),
    CHECK (
        (status = 'CREATED' AND starts_at IS NULL)
        OR (status IN ('ACTIVE', 'CLOSING', 'CLOSED')
            AND starts_at IS NOT NULL
            AND planned_ends_at IS NOT NULL
            AND intake_until IS NOT NULL
            AND closing_deadline_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX survey_session_one_open_idx
    ON survey_session (merchant_id, group_id)
    WHERE status IN ('ACTIVE', 'CLOSING');

CREATE TABLE survey_revision (
    merchant_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    PRIMARY KEY (merchant_id, survey_id, revision_id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, revision_id) REFERENCES sample_revision(merchant_id, id)
);

CREATE TABLE poll_kind (
    code varchar(30) PRIMARY KEY
);
INSERT INTO poll_kind (code) VALUES ('PREFERENCE'), ('PURCHASE_INTENT');

CREATE TABLE survey_poll (
    merchant_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    id uuid NOT NULL,
    revision_id uuid NOT NULL,
    kind varchar(30) NOT NULL REFERENCES poll_kind(code),
    question varchar(240) NOT NULL CHECK (length(btrim(question)) > 0),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id, revision_id)
        REFERENCES survey_revision(merchant_id, survey_id, revision_id)
);

CREATE TABLE survey_poll_option (
    merchant_id uuid NOT NULL,
    poll_id uuid NOT NULL,
    id uuid NOT NULL,
    label varchar(160) NOT NULL CHECK (length(btrim(label)) > 0),
    position int NOT NULL CHECK (position >= 0),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, poll_id, id),
    UNIQUE (merchant_id, poll_id, position),
    FOREIGN KEY (merchant_id, poll_id) REFERENCES survey_poll(merchant_id, id)
);

CREATE TABLE survey_vote (
    merchant_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    poll_id uuid NOT NULL,
    member_id uuid NOT NULL,
    option_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, survey_id, poll_id, member_id),
    FOREIGN KEY (merchant_id, survey_id, poll_id)
        REFERENCES survey_poll(merchant_id, survey_id, id),
    FOREIGN KEY (merchant_id, poll_id, option_id)
        REFERENCES survey_poll_option(merchant_id, poll_id, id),
    FOREIGN KEY (merchant_id, member_id) REFERENCES merchant_member(merchant_id, principal_id)
);

CREATE TABLE group_message (
    merchant_id uuid NOT NULL,
    group_id uuid NOT NULL,
    id uuid NOT NULL,
    sender_id uuid NOT NULL,
    client_msg_id uuid NOT NULL,
    group_seq bigint NOT NULL CHECK (group_seq > 0),
    payload_hash varchar(64) NOT NULL,
    text_body varchar(4000),
    reply_to_id uuid,
    card_revision_id uuid,
    survey_id uuid,
    received_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, group_id, group_seq),
    UNIQUE (merchant_id, group_id, sender_id, client_msg_id),
    FOREIGN KEY (merchant_id, group_id) REFERENCES craft_group(merchant_id, id),
    FOREIGN KEY (merchant_id, group_id, sender_id)
        REFERENCES group_member(merchant_id, group_id, principal_id),
    FOREIGN KEY (merchant_id, reply_to_id) REFERENCES group_message(merchant_id, id),
    FOREIGN KEY (merchant_id, card_revision_id) REFERENCES sample_revision(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id),
    CHECK (text_body IS NOT NULL OR card_revision_id IS NOT NULL)
);

CREATE TABLE message_survey_link (
    merchant_id uuid NOT NULL,
    message_id uuid NOT NULL,
    survey_id uuid NOT NULL,
    linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, message_id, survey_id),
    FOREIGN KEY (merchant_id, message_id) REFERENCES group_message(merchant_id, id),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id)
);

CREATE TABLE message_asset (
    merchant_id uuid NOT NULL,
    message_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    PRIMARY KEY (merchant_id, message_id, asset_id),
    FOREIGN KEY (merchant_id, message_id) REFERENCES group_message(merchant_id, id),
    FOREIGN KEY (merchant_id, asset_id) REFERENCES asset(merchant_id, id)
);

CREATE TABLE subscription_status (
    code varchar(20) PRIMARY KEY
);
INSERT INTO subscription_status (code) VALUES ('ACTIVE'), ('REVOKED'), ('EXPIRED');

CREATE TABLE subscription (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    scope_key varchar(120) NOT NULL,
    rule_version varchar(40) NOT NULL,
    status varchar(20) NOT NULL REFERENCES subscription_status(code),
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, id),
    UNIQUE (merchant_id, survey_id, scope_key),
    FOREIGN KEY (merchant_id, survey_id) REFERENCES survey_session(merchant_id, id)
);

CREATE TABLE outbox_status (
    code varchar(20) PRIMARY KEY
);
INSERT INTO outbox_status (code) VALUES ('PENDING'), ('IN_FLIGHT'), ('DELIVERED'), ('FAILED'), ('DLQ');

CREATE TABLE outbox (
    merchant_id uuid NOT NULL,
    id uuid NOT NULL,
    aggregate_type varchar(60) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    payload_json jsonb NOT NULL,
    status varchar(20) NOT NULL REFERENCES outbox_status(code),
    attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_owner varchar(80),
    lease_epoch bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    last_error varchar(400),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (id),
    UNIQUE (merchant_id, id)
);

CREATE INDEX outbox_claim_idx
    ON outbox (available_at, status)
    WHERE status IN ('PENDING', 'IN_FLIGHT', 'FAILED');

CREATE TABLE consumer_receipt (
    merchant_id uuid NOT NULL,
    consumer_name varchar(80) NOT NULL,
    event_id uuid NOT NULL,
    effect_key varchar(160) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, consumer_name, event_id),
    UNIQUE (merchant_id, consumer_name, effect_key),
    FOREIGN KEY (merchant_id, event_id) REFERENCES outbox(merchant_id, id)
);

CREATE TABLE api_command (
    merchant_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    resource_type varchar(60) NOT NULL,
    resource_id uuid NOT NULL,
    command_key uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (merchant_id, actor_id, resource_type, resource_id, command_key),
    FOREIGN KEY (merchant_id, actor_id) REFERENCES merchant_member(merchant_id, principal_id)
);
