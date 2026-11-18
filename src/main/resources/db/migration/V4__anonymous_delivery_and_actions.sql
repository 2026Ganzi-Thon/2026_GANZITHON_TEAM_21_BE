ALTER TABLE receipt ADD COLUMN action_token_hash CHAR(64);
ALTER TABLE receipt ADD COLUMN action_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE receipt ADD COLUMN action_closed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE receipt ADD CONSTRAINT receipt_action_pair CHECK
    ((action_token_hash IS NULL AND action_expires_at IS NULL)
      OR (action_token_hash IS NOT NULL AND action_expires_at IS NOT NULL));

CREATE TABLE anon_daily_usage (
    user_id UUID NOT NULL REFERENCES app_user(id),
    usage_date DATE NOT NULL,
    sent_count SMALLINT NOT NULL DEFAULT 0,
    received_count SMALLINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(user_id,usage_date),
    CONSTRAINT anon_usage_limits CHECK (sent_count BETWEEN 0 AND 5 AND received_count BETWEEN 0 AND 3)
);
CREATE INDEX anon_daily_usage_date_idx ON anon_daily_usage(usage_date);

CREATE TABLE receipt_reaction (
    receipt_id UUID PRIMARY KEY REFERENCES receipt(id),
    code VARCHAR(20) NOT NULL REFERENCES reaction_catalog(code),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE receipt_report (
    id UUID PRIMARY KEY,
    receipt_id UUID NOT NULL UNIQUE REFERENCES receipt(id),
    reason_code VARCHAR(24) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT receipt_report_reason CHECK
        (reason_code IN ('INAPPROPRIATE','UNWANTED_REPEATED','HARASSMENT_THREAT','OTHER')),
    CONSTRAINT receipt_report_detail CHECK (reason_code='OTHER' OR detail IS NULL)
);

CREATE TABLE anonymous_block (
    blocker_id UUID NOT NULL REFERENCES app_user(id),
    blocked_id UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(blocker_id,blocked_id),
    CONSTRAINT anonymous_block_distinct CHECK (blocker_id<>blocked_id)
);
CREATE INDEX anonymous_block_reverse_idx ON anonymous_block(blocked_id,blocker_id);
