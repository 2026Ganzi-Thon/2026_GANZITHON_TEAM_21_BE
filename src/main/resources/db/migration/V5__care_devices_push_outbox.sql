CREATE TABLE care_notice (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    level SMALLINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL,
    content_version INTEGER NOT NULL,
    qualified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    ack_action VARCHAR(12),
    UNIQUE(user_id,level),
    CONSTRAINT care_notice_level CHECK (level IN (1,2)),
    CONSTRAINT care_notice_ack CHECK ((acknowledged_at IS NULL AND ack_action IS NULL)
      OR (acknowledged_at IS NOT NULL AND ack_action IN ('VIEWED','DISMISSED')))
);

-- Intentionally empty until product-reviewed level copy is supplied.
CREATE TABLE care_content (
    level SMALLINT PRIMARY KEY,
    version INTEGER NOT NULL,
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT care_content_level CHECK (level IN (1,2)),
    CONSTRAINT care_content_version CHECK (version>0)
);

CREATE TABLE care_resource (
    id VARCHAR(64) PRIMARY KEY,
    version INTEGER NOT NULL,
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL,
    url TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT care_resource_version CHECK (version>0)
);

CREATE TABLE care_notice_resource (
    notice_id UUID NOT NULL REFERENCES care_notice(id),
    resource_id VARCHAR(64) NOT NULL REFERENCES care_resource(id),
    position SMALLINT NOT NULL,
    PRIMARY KEY(notice_id,resource_id),
    UNIQUE(notice_id,position),
    CONSTRAINT care_notice_resource_position CHECK (position>=1)
);

CREATE TABLE device_registration (
    installation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    session_id UUID NOT NULL REFERENCES auth_session(id),
    platform VARCHAR(8) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    token_ciphertext BYTEA NOT NULL,
    key_version SMALLINT NOT NULL DEFAULT 1,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT device_platform CHECK (platform IN ('IOS','ANDROID'))
);
CREATE INDEX device_registration_user_idx ON device_registration(user_id);
CREATE INDEX device_registration_session_idx ON device_registration(session_id);

CREATE TABLE device_registration_guard (id SMALLINT PRIMARY KEY);
INSERT INTO device_registration_guard(id) VALUES (1);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    type VARCHAR(16) NOT NULL,
    receipt_id UUID NOT NULL REFERENCES receipt(id),
    installation_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    lease_token UUID,
    last_error_code VARCHAR(64),
    provider_ticket_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(receipt_id,installation_id),
    CONSTRAINT outbox_type CHECK (type='PUSH_RECEIPT'),
    CONSTRAINT outbox_status CHECK (status IN ('PENDING','PROCESSING','SENT','SKIPPED','FAILED')),
    CONSTRAINT outbox_attempt CHECK (attempt_count>=0)
);
CREATE INDEX outbox_due_idx ON outbox_event(status,available_at,id);
CREATE INDEX outbox_lease_idx ON outbox_event(status,lease_until);
