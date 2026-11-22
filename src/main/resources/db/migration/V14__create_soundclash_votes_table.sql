CREATE TABLE soundclash_votes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    battle_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_soundclash_vote_battle
        FOREIGN KEY (battle_id)
            REFERENCES soundclash_battles(id)
            ON DELETE CASCADE,

    CONSTRAINT fk_soundclash_vote_candidate
        FOREIGN KEY (candidate_id)
            REFERENCES soundclash_candidates(id)
            ON DELETE CASCADE,

    CONSTRAINT fk_soundclash_vote_user
        FOREIGN KEY (user_id)
            REFERENCES users(id),

    CONSTRAINT uk_soundclash_vote_battle_user
        UNIQUE (battle_id, user_id)
);

CREATE INDEX idx_soundclash_votes_battle_candidate ON soundclash_votes(battle_id, candidate_id);
