CREATE TABLE tracks (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        artist VARCHAR(255) NOT NULL,
                        duration_seconds INT NOT NULL,
                        audio_asset_url TEXT,
                        bpm DECIMAL(6,2),
                        camelot_key VARCHAR(5),
                        energy DECIMAL(4,3),
                        valence DECIMAL(4,3)
);