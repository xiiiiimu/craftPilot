CREATE TABLE principal (
    id uuid PRIMARY KEY,
    login varchar(120) NOT NULL UNIQUE CHECK (login = lower(login) AND length(login) > 0),
    display_name varchar(120) NOT NULL CHECK (length(btrim(display_name)) > 0),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE merchant (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE merchant_role (
    code varchar(20) PRIMARY KEY
);

INSERT INTO merchant_role (code) VALUES ('OWNER'), ('MEMBER');

CREATE TABLE merchant_member (
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    principal_id uuid NOT NULL REFERENCES principal(id),
    role_code varchar(20) NOT NULL REFERENCES merchant_role(code),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (merchant_id, principal_id)
);

CREATE INDEX merchant_member_principal_active_idx
    ON merchant_member (principal_id, merchant_id) WHERE revoked_at IS NULL;

CREATE TABLE dev_credential (
    principal_id uuid PRIMARY KEY REFERENCES principal(id),
    password_hash varchar(100) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    CHECK (expires_at > created_at)
);

CREATE TABLE membership_revoke_command (
    merchant_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    command_key uuid NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (merchant_id, actor_id, command_key),
    FOREIGN KEY (merchant_id, actor_id) REFERENCES merchant_member(merchant_id, principal_id),
    FOREIGN KEY (merchant_id, target_id) REFERENCES merchant_member(merchant_id, principal_id)
);
