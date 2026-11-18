CREATE INDEX receipt_unread_lookup_idx ON receipt(recipient_id, opened_at, received_at DESC, id DESC);
CREATE INDEX emotion_record_owner_side_idx ON emotion_record(owner_id, side, recorded_at DESC, id DESC);
CREATE INDEX emotion_record_dispatch_idx ON emotion_record(dispatch_id);
