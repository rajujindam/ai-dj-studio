ALTER TABLE interactions ADD COLUMN lounge_id BIGINT NULL;

ALTER TABLE interactions
    ADD CONSTRAINT fk_interaction_lounge
        FOREIGN KEY (lounge_id)
            REFERENCES dj_lounges(id)
            ON DELETE SET NULL;

CREATE INDEX idx_interactions_user_track ON interactions(user_id, track_id);
CREATE INDEX idx_interactions_lounge ON interactions(lounge_id);
