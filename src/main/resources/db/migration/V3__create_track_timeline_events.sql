CREATE TABLE track_timeline_events (
                                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                       track_id BIGINT NOT NULL,
                                       ts_seconds INT NOT NULL,
                                       event_type VARCHAR(50) NOT NULL,
                                       payload JSONB,

                                       CONSTRAINT fk_timeline_track
                                           FOREIGN KEY (track_id)
                                               REFERENCES tracks(id)
);