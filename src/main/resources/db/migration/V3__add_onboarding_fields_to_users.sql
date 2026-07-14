ALTER TABLE users
    ADD COLUMN job_preference VARCHAR(20),
    ADD COLUMN profile_picture_url VARCHAR(500),
    ADD COLUMN resume_url VARCHAR(500),
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED';

CREATE TABLE user_job_functions (
    user_id BIGINT NOT NULL REFERENCES users(id),
    value VARCHAR(255) NOT NULL
);

CREATE TABLE user_preferred_locations (
    user_id BIGINT NOT NULL REFERENCES users(id),
    value VARCHAR(255) NOT NULL
);

CREATE INDEX ix_user_job_functions_user_id ON user_job_functions (user_id);
CREATE INDEX ix_user_preferred_locations_user_id ON user_preferred_locations (user_id);
