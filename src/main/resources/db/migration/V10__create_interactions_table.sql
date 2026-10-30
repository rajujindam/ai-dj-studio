CREATE TABLE interactions (
                              id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                              user_id BIGINT NOT NULL,
                              track_id BIGINT NOT NULL,
                              played_seconds INT NOT NULL DEFAULT 0,
                              completed BOOLEAN NOT NULL DEFAULT FALSE,
                              liked BOOLEAN NOT NULL DEFAULT FALSE,
                              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_interaction_user
                                  FOREIGN KEY (user_id)
                                      REFERENCES users(id),

                              CONSTRAINT fk_interaction_track
                                  FOREIGN KEY (track_id)
                                      REFERENCES tracks(id)
);