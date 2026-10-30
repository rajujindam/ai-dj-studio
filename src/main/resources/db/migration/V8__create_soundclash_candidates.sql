CREATE TABLE soundclash_candidates (
                                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                       battle_id BIGINT NOT NULL,
                                       track_id BIGINT NOT NULL,
                                       submitted_by_user_id BIGINT NOT NULL,
                                       vote_count INT NOT NULL DEFAULT 0,

                                       CONSTRAINT fk_candidate_battle
                                           FOREIGN KEY (battle_id)
                                               REFERENCES soundclash_battles(id),

                                       CONSTRAINT fk_candidate_track
                                           FOREIGN KEY (track_id)
                                               REFERENCES tracks(id),

                                       CONSTRAINT fk_candidate_user
                                           FOREIGN KEY (submitted_by_user_id)
                                               REFERENCES users(id)
);