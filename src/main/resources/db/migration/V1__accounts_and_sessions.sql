CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    login_id VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(20) NOT NULL,
    adult_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    adult_checked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT app_user_login_length CHECK (CHAR_LENGTH(login_id) BETWEEN 4 AND 20),
    CONSTRAINT app_user_nickname_valid CHECK (CHAR_LENGTH(TRIM(nickname)) BETWEEN 1 AND 20 AND nickname = TRIM(nickname)),
    CONSTRAINT app_user_adult_status CHECK (adult_status IN ('UNKNOWN', 'VERIFIED_19_PLUS', 'INELIGIBLE'))
);

CREATE INDEX app_user_adult_status_idx ON app_user(adult_status, id);

CREATE TABLE user_settings (
    user_id UUID PRIMARY KEY REFERENCES app_user(id),
    anonymous_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rest_mode VARCHAR(12) NOT NULL DEFAULT 'OFF',
    rest_starts_at TIMESTAMP WITH TIME ZONE,
    rest_ends_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT user_settings_rest_valid CHECK (
        (rest_mode = 'OFF' AND rest_starts_at IS NULL AND rest_ends_at IS NULL)
        OR (rest_mode = 'MANUAL' AND rest_starts_at IS NOT NULL AND rest_ends_at IS NULL)
        OR (rest_mode = 'SCHEDULED' AND rest_starts_at IS NOT NULL AND rest_ends_at IS NOT NULL AND rest_ends_at > rest_starts_at)
    )
);

CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    access_token_hash CHAR(64) NOT NULL UNIQUE,
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    access_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    refresh_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    rotation_number INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT auth_session_expiry_valid CHECK (access_expires_at > created_at AND refresh_expires_at >= access_expires_at),
    CONSTRAINT auth_session_rotation_valid CHECK (rotation_number >= 0)
);

CREATE INDEX auth_session_user_idx ON auth_session(user_id, created_at DESC, id DESC);
CREATE INDEX auth_session_refresh_expiry_idx ON auth_session(refresh_expires_at);
