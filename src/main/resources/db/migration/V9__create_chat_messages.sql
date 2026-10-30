CREATE TABLE chat_messages (
                               id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               lounge_id BIGINT NOT NULL,
                               user_id BIGINT,
                               msg_type VARCHAR(30) NOT NULL,
                               body TEXT NOT NULL,
                               sentiment_score DECIMAL(4,3),
                               ts_seconds INT,
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               CONSTRAINT fk_chat_lounge
                                   FOREIGN KEY (lounge_id)
                                       REFERENCES dj_lounges(id),

                               CONSTRAINT fk_chat_user
                                   FOREIGN KEY (user_id)
                                       REFERENCES users(id)
);