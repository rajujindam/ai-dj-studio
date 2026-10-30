CREATE TABLE dj_lounges (
                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            host_user_id BIGINT NOT NULL,
                            vibe_profile JSONB,
                            current_track_id BIGINT,
                            status VARCHAR(30) NOT NULL,
                            bpm_tolerance_pct DECIMAL(5,2),
                            soundclash_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                            ai_verbosity VARCHAR(20),
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            ended_at TIMESTAMP,

                            CONSTRAINT fk_lounge_host
                                FOREIGN KEY (host_user_id)
                                    REFERENCES users(id),

                            CONSTRAINT fk_lounge_current_track
                                FOREIGN KEY (current_track_id)
                                    REFERENCES tracks(id)
);