ALTER TABLE app_user ADD CONSTRAINT app_user_login_format
    CHECK (login_id ~ '^[a-z0-9_]{4,20}$');

CREATE UNIQUE INDEX friend_request_pending_pair_idx
    ON friend_request (LEAST(from_user_id,to_user_id), GREATEST(from_user_id,to_user_id))
    WHERE status='PENDING';

CREATE UNIQUE INDEX emotion_record_own_dispatch_idx
    ON emotion_record(dispatch_id) WHERE side='OWN';
