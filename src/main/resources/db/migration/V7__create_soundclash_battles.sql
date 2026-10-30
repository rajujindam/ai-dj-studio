CREATE TABLE soundclash_battles (
                                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                    lounge_id BIGINT NOT NULL,
                                    opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    closes_at TIMESTAMP NOT NULL,
                                    status VARCHAR(30) NOT NULL,
                                    winning_track_id BIGINT,

                                    CONSTRAINT fk_battle_lounge
                                        FOREIGN KEY (lounge_id)
                                            REFERENCES dj_lounges(id),

                                    CONSTRAINT fk_battle_winning_track
                                        FOREIGN KEY (winning_track_id)
                                            REFERENCES tracks(id)
);