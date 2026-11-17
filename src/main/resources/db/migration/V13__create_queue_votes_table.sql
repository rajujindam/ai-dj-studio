CREATE TABLE queue_votes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lounge_id BIGINT NOT NULL,
    queue_entry_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_queue_vote_lounge
        FOREIGN KEY (lounge_id)
            REFERENCES dj_lounges(id),

    CONSTRAINT fk_queue_vote_entry
        FOREIGN KEY (queue_entry_id)
            REFERENCES queue_entries(id)
            ON DELETE CASCADE,

    CONSTRAINT fk_queue_vote_user
        FOREIGN KEY (user_id)
            REFERENCES users(id),

    CONSTRAINT uk_queue_vote_entry_user
        UNIQUE (queue_entry_id, user_id)
);

CREATE INDEX idx_queue_votes_lounge_entry ON queue_votes(lounge_id, queue_entry_id);
