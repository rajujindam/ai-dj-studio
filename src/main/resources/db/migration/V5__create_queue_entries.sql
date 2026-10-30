CREATE TABLE queue_entries (
                               id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               lounge_id BIGINT NOT NULL,
                               track_id BIGINT NOT NULL,
                               position INT NOT NULL,
                               vote_count INT NOT NULL DEFAULT 0,
                               added_by_user_id BIGINT NOT NULL,

                               CONSTRAINT fk_queue_lounge
                                   FOREIGN KEY (lounge_id)
                                       REFERENCES dj_lounges(id),

                               CONSTRAINT fk_queue_track
                                   FOREIGN KEY (track_id)
                                       REFERENCES tracks(id),

                               CONSTRAINT fk_queue_user
                                   FOREIGN KEY (added_by_user_id)
                                       REFERENCES users(id)
);