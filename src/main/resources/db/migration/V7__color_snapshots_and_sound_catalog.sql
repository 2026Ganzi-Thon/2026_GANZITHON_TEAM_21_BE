CREATE TABLE orb_color_snapshot (
    orb_id UUID NOT NULL REFERENCES orb(id),
    position SMALLINT NOT NULL,
    color VARCHAR(8) NOT NULL,
    area VARCHAR(40) NOT NULL,
    word_1 VARCHAR(20) NOT NULL,
    word_2 VARCHAR(20) NOT NULL,
    word_3 VARCHAR(20) NOT NULL,
    word_4 VARCHAR(20) NOT NULL,
    PRIMARY KEY (orb_id, position),
    UNIQUE (orb_id, color),
    CONSTRAINT orb_color_position CHECK (position IN (1,2))
);

CREATE TABLE sound_catalog (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    color VARCHAR(8),
    duration_ms INTEGER NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT sound_duration CHECK (duration_ms BETWEEN 1 AND 15000),
    CONSTRAINT sound_color CHECK (color IS NULL OR color IN ('YELLOW','ORANGE','PINK','RED','PURPLE','BLUE','GREEN'))
);

ALTER TABLE orb ADD COLUMN sound_id UUID REFERENCES sound_catalog(id);
CREATE INDEX orb_sound_idx ON orb(sound_id);
