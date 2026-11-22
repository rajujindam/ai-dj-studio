-- V15: Add moderation and room lock columns to dj_lounges and lounge_participants.

ALTER TABLE dj_lounges
    ADD COLUMN IF NOT EXISTS is_locked BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE lounge_participants
    ADD COLUMN IF NOT EXISTS is_muted BOOLEAN NOT NULL DEFAULT FALSE;
