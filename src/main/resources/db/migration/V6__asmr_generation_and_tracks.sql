CREATE TABLE asmr_generation (
    id UUID PRIMARY KEY,
    orb_id UUID NOT NULL UNIQUE REFERENCES orb(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    track_count SMALLINT NOT NULL DEFAULT 3,
    description VARCHAR(300),
    detached_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT asmr_generation_status CHECK (status IN ('QUEUED','PROCESSING','PARTIAL_FAILED','FAILED','READY')),
    CONSTRAINT asmr_generation_count CHECK (track_count=3)
);

CREATE TABLE asmr_track (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES asmr_generation(id),
    position SMALLINT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'QUEUED',
    attempt INTEGER NOT NULL DEFAULT 1,
    provider VARCHAR(40),
    provider_job_id VARCHAR(255),
    provider_request_key VARCHAR(100),
    title VARCHAR(100),
    duration_ms INTEGER,
    storage_key VARCHAR(512),
    error_code VARCHAR(40),
    attempt_deadline_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(generation_id,position),
    UNIQUE(provider,provider_job_id),
    UNIQUE(provider,provider_request_key),
    CONSTRAINT asmr_track_position CHECK (position BETWEEN 1 AND 3),
    CONSTRAINT asmr_track_attempt CHECK (attempt>=1),
    CONSTRAINT asmr_track_status CHECK (status IN ('QUEUED','PROCESSING','FAILED','READY')),
    CONSTRAINT asmr_track_result CHECK (
        (status='READY' AND title IS NOT NULL AND storage_key IS NOT NULL
            AND duration_ms BETWEEN 1 AND 30000 AND error_code IS NULL)
        OR (status='FAILED' AND error_code IS NOT NULL AND title IS NULL
            AND storage_key IS NULL AND duration_ms IS NULL)
        OR (status IN ('QUEUED','PROCESSING') AND title IS NULL
            AND storage_key IS NULL AND duration_ms IS NULL AND error_code IS NULL)
    )
);
CREATE INDEX asmr_track_status_deadline_idx ON asmr_track(status,attempt_deadline_at);

CREATE TABLE asmr_outbox_event (
    id UUID PRIMARY KEY,
    track_id UUID NOT NULL REFERENCES asmr_track(id),
    attempt INTEGER NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(track_id,attempt),
    CONSTRAINT asmr_outbox_attempt CHECK (attempt>=1),
    CONSTRAINT asmr_outbox_status CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED')),
    CONSTRAINT asmr_outbox_lease CHECK ((status='PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX asmr_outbox_due_idx ON asmr_outbox_event(status,available_at,id);
CREATE INDEX asmr_outbox_lease_idx ON asmr_outbox_event(status,lease_until);
