ALTER TABLE app_user ADD COLUMN birth_date DATE;
ALTER TABLE app_user DROP CONSTRAINT app_user_adult_status;
ALTER TABLE app_user ADD CONSTRAINT app_user_adult_status CHECK (adult_status IN
    ('UNKNOWN', 'VERIFIED_19_PLUS', 'INELIGIBLE', 'SELF_REPORTED_19_PLUS', 'SELF_REPORTED_UNDER_19'));
