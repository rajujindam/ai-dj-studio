CREATE TABLE queue_events (
                              id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                              lounge_id BIGINT NOT NULL,
                              user_id BIGINT,
                              event_type VARCHAR(50) NOT NULL,
                              payload JSONB,
                              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_queue_event_lounge
                                  FOREIGN KEY (lounge_id)
                                      REFERENCES dj_lounges(id),

                              CONSTRAINT fk_queue_event_user
                                  FOREIGN KEY (user_id)
                                      REFERENCES users(id)
);