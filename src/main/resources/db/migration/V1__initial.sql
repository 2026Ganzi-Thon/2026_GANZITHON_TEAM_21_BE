CREATE TABLE app_user (
                          id VARCHAR(36) PRIMARY KEY,
                          login_id VARCHAR(30) NOT NULL UNIQUE,
                          password_hash VARCHAR(100) NOT NULL,
                          qr_token VARCHAR(64) NOT NULL UNIQUE,
                          resting BOOLEAN NOT NULL DEFAULT FALSE,
                          premium BOOLEAN NOT NULL DEFAULT FALSE,
                          created_at BIGINT NOT NULL
);

CREATE TABLE auth_session (
                              token_hash VARCHAR(64) PRIMARY KEY,
                              user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                              expires_at BIGINT NOT NULL
);

CREATE TABLE friendship (
                            id VARCHAR(36) PRIMARY KEY,
                            low_user VARCHAR(36) NOT NULL REFERENCES app_user(id),
                            high_user VARCHAR(36) NOT NULL REFERENCES app_user(id),
                            requester VARCHAR(36) NOT NULL REFERENCES app_user(id),
                            status VARCHAR(12) NOT NULL,
                            created_at BIGINT NOT NULL,
                            UNIQUE (low_user, high_user)
);

CREATE TABLE bead (
                      id VARCHAR(36) PRIMARY KEY,
                      sender_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                      color_first VARCHAR(10) NOT NULL,
                      color_second VARCHAR(10),
                      music_color VARCHAR(10),
                      self_saved BOOLEAN NOT NULL,
                      created_at BIGINT NOT NULL,
                      request_key VARCHAR(80) NOT NULL,
                      request_hash VARCHAR(64) NOT NULL,
                      UNIQUE (sender_id, request_key)
);

CREATE TABLE delivery (
                          id VARCHAR(36) PRIMARY KEY,
                          bead_id VARCHAR(36) NOT NULL REFERENCES bead(id),
                          recipient_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                          received_at BIGINT NOT NULL,
                          opened_at BIGINT,
                          listening_mode VARCHAR(10),
                          UNIQUE (bead_id, recipient_id)
);

CREATE TABLE send_skip (
                           bead_id VARCHAR(36) NOT NULL REFERENCES bead(id),
                           user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                           reason VARCHAR(20) NOT NULL,
                           PRIMARY KEY (bead_id, user_id)
);

CREATE TABLE emoji_catalog (
                               emoji VARCHAR(32) PRIMARY KEY
);

CREATE TABLE reaction (
                          id VARCHAR(36) PRIMARY KEY,
                          bead_id VARCHAR(36) NOT NULL REFERENCES bead(id),
                          actor_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                          emoji VARCHAR(32) NOT NULL REFERENCES emoji_catalog(emoji),
                          created_at BIGINT NOT NULL,
                          UNIQUE (bead_id, actor_id, emoji)
);

CREATE TABLE push_subscription (
                                   id VARCHAR(36) PRIMARY KEY,
                                   user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                                   endpoint VARCHAR(2048) NOT NULL UNIQUE,
                                   p256dh VARCHAR(200) NOT NULL,
                                   auth VARCHAR(100) NOT NULL
);

CREATE TABLE push_outbox (
                             id VARCHAR(36) PRIMARY KEY,
                             user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
                             bead_id VARCHAR(36) NOT NULL REFERENCES bead(id),
                             event_type VARCHAR(20) NOT NULL,
                             status VARCHAR(16) NOT NULL,
                             attempts INT NOT NULL DEFAULT 0,
                             next_attempt BIGINT NOT NULL,
                             created_at BIGINT NOT NULL
);

CREATE INDEX delivery_user_idx
    ON delivery(recipient_id, received_at);

CREATE INDEX bead_sender_idx
    ON bead(sender_id, created_at);

CREATE INDEX push_pending_idx
    ON push_outbox(status, next_attempt);