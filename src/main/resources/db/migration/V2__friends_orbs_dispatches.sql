CREATE TABLE friend_request (
    id UUID PRIMARY KEY,
    from_user_id UUID NOT NULL REFERENCES app_user(id),
    to_user_id UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(12) NOT NULL,
    pending_pair_key VARCHAR(73) UNIQUE,
    decided_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT friend_request_valid CHECK (
        from_user_id <> to_user_id AND
        ((status = 'PENDING' AND pending_pair_key IS NOT NULL AND decided_at IS NULL)
         OR (status IN ('ACCEPTED','DECLINED') AND pending_pair_key IS NULL AND decided_at IS NOT NULL))
    )
);
CREATE INDEX friend_request_incoming_idx ON friend_request(to_user_id, created_at DESC, id DESC);
CREATE INDEX friend_request_outgoing_idx ON friend_request(from_user_id, created_at DESC, id DESC);

CREATE TABLE friendship (
    user_low_id UUID NOT NULL REFERENCES app_user(id),
    user_high_id UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(user_low_id,user_high_id),
    CONSTRAINT friendship_ordered CHECK (user_low_id < user_high_id)
);
CREATE INDEX friendship_high_idx ON friendship(user_high_id, created_at DESC, user_low_id);

CREATE TABLE emotion_catalog (
    version INTEGER NOT NULL,
    id VARCHAR(32) NOT NULL,
    color VARCHAR(8) NOT NULL,
    area VARCHAR(40) NOT NULL,
    word VARCHAR(20) NOT NULL,
    is_care BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY(version,id),
    CONSTRAINT emotion_catalog_valid CHECK (version > 0 AND color IN ('YELLOW','ORANGE','PINK','RED','PURPLE','BLUE','GREEN'))
);

CREATE TABLE reaction_catalog (
    code VARCHAR(20) PRIMARY KEY,
    label VARCHAR(40) NOT NULL,
    emoji VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE orb (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES app_user(id),
    kind VARCHAR(8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT orb_kind_valid CHECK (kind IN ('SINGLE','MIXED'))
);
CREATE INDEX orb_author_idx ON orb(author_id, created_at DESC, id DESC);

CREATE TABLE orb_emotion (
    orb_id UUID NOT NULL REFERENCES orb(id),
    position SMALLINT NOT NULL,
    catalog_version INTEGER NOT NULL,
    emotion_id VARCHAR(32) NOT NULL,
    color VARCHAR(8) NOT NULL,
    area VARCHAR(40) NOT NULL,
    word VARCHAR(20) NOT NULL,
    is_care BOOLEAN NOT NULL,
    PRIMARY KEY(orb_id,position),
    UNIQUE(orb_id,color),
    FOREIGN KEY(catalog_version,emotion_id) REFERENCES emotion_catalog(version,id),
    CONSTRAINT orb_emotion_position_valid CHECK (position IN (1,2))
);

CREATE TABLE dispatch (
    id UUID PRIMARY KEY,
    orb_id UUID NOT NULL UNIQUE REFERENCES orb(id),
    route VARCHAR(12) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cancel_until TIMESTAMP WITH TIME ZONE,
    recipient_count SMALLINT NOT NULL,
    CONSTRAINT dispatch_route_valid CHECK (
        (route='SELF' AND recipient_count=0 AND cancel_until IS NULL)
        OR (route='FRIEND' AND recipient_count=1 AND cancel_until IS NOT NULL)
        OR (route='ANONYMOUS' AND recipient_count BETWEEN 1 AND 3 AND cancel_until IS NOT NULL)
    )
);

CREATE TABLE receipt (
    id UUID PRIMARY KEY,
    target_id UUID NOT NULL UNIQUE,
    dispatch_id UUID NOT NULL REFERENCES dispatch(id),
    recipient_id UUID NOT NULL REFERENCES app_user(id),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(dispatch_id,recipient_id),
    CONSTRAINT receipt_open_cancel_valid CHECK (opened_at IS NULL OR cancelled_at IS NULL)
);
CREATE INDEX receipt_inbox_idx ON receipt(recipient_id, received_at DESC, id DESC);

CREATE TABLE emotion_record (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    dispatch_id UUID NOT NULL REFERENCES dispatch(id),
    own_dispatch_id UUID UNIQUE REFERENCES dispatch(id),
    receipt_id UUID UNIQUE REFERENCES receipt(id),
    side VARCHAR(8) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT emotion_record_side_valid CHECK (
        (side='OWN' AND own_dispatch_id=dispatch_id AND receipt_id IS NULL)
        OR (side='RECEIVED' AND own_dispatch_id IS NULL AND receipt_id IS NOT NULL AND deleted_at IS NULL)
    )
);
CREATE INDEX emotion_record_owner_idx ON emotion_record(owner_id, recorded_at DESC, id DESC);

CREATE TABLE idempotency_request (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    method VARCHAR(8) NOT NULL,
    resource_path VARCHAR(255) NOT NULL,
    request_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    http_status SMALLINT NOT NULL,
    response_json TEXT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(user_id,method,resource_path,request_key)
);
CREATE INDEX idempotency_expiry_idx ON idempotency_request(expires_at);

INSERT INTO emotion_catalog(version,id,color,area,word,is_care) VALUES
(1,'yellow_01','YELLOW','기쁨·기대','기쁨',FALSE),
(1,'yellow_02','YELLOW','기쁨·기대','기대',FALSE),
(1,'yellow_03','YELLOW','기쁨·기대','홀가분함',FALSE),
(1,'yellow_04','YELLOW','기쁨·기대','감사',FALSE),
(1,'orange_01','ORANGE','활력·설렘','설렘',FALSE),
(1,'orange_02','ORANGE','활력·설렘','의욕',FALSE),
(1,'orange_03','ORANGE','활력·설렘','뿌듯함',FALSE),
(1,'orange_04','ORANGE','활력·설렘','들뜸',FALSE),
(1,'pink_01','PINK','애정·다정함','애정',FALSE),
(1,'pink_02','PINK','애정·다정함','다정함',FALSE),
(1,'pink_03','PINK','애정·다정함','그리움',FALSE),
(1,'pink_04','PINK','애정·다정함','위로받고 싶음',FALSE),
(1,'red_01','RED','강한 감정','짜증',FALSE),
(1,'red_02','RED','강한 감정','화남',FALSE),
(1,'red_03','RED','강한 감정','억울함',FALSE),
(1,'red_04','RED','강한 감정','벅참',FALSE),
(1,'purple_01','PURPLE','복잡함·불안','불안',TRUE),
(1,'purple_02','PURPLE','복잡함·불안','막막함',TRUE),
(1,'purple_03','PURPLE','복잡함·불안','복잡함',FALSE),
(1,'purple_04','PURPLE','복잡함·불안','생각이 많음',FALSE),
(1,'blue_01','BLUE','가라앉음','가라앉음',FALSE),
(1,'blue_02','BLUE','가라앉음','외로움',TRUE),
(1,'blue_03','BLUE','가라앉음','무기력',TRUE),
(1,'blue_04','BLUE','가라앉음','지침',TRUE),
(1,'green_01','GREEN','안정·회복','편안함',FALSE),
(1,'green_02','GREEN','안정·회복','안정',FALSE),
(1,'green_03','GREEN','안정·회복','회복 중',FALSE),
(1,'green_04','GREEN','안정·회복','평온',FALSE);

INSERT INTO reaction_catalog(code,label,emoji) VALUES
('HEART','마음 전하기','❤️'),
('HUG','안아주기','🫂'),
('CHEER','응원하기','🙌');
