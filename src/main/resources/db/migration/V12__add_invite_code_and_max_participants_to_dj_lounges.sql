-- V12: Add invite_code and max_participants to dj_lounges for F03 lounge creation

ALTER TABLE dj_lounges
    ADD COLUMN invite_code VARCHAR(20) UNIQUE,
    ADD COLUMN max_participants INT DEFAULT 50;

CREATE INDEX idx_dj_lounges_invite_code ON dj_lounges (invite_code);
