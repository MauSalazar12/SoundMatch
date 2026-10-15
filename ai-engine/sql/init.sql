
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE songs (
    id BIGINT PRIMARY KEY,
    title TEXT NOT NULL,
    artist TEXT,
    genre TEXT,
    audio_path TEXT NOT NULL,
    duration_seconds DOUBLE PRECISION,

    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    processed_at TIMESTAMPTZ,

    CONSTRAINT valid_song_status CHECK (
        status IN ('pending', 'processing',
                   'processed', 'failed')
    )
);

CREATE TABLE audio_embeddings (
    id BIGSERIAL PRIMARY KEY,

    song_id BIGINT NOT NULL
        REFERENCES songs(id) ON DELETE CASCADE,

    segment_index INTEGER NOT NULL,
    start_seconds DOUBLE PRECISION NOT NULL,
    end_seconds DOUBLE PRECISION NOT NULL,

    embedding VECTOR(512) NOT NULL,

    model_version TEXT NOT NULL,

    UNIQUE (
        song_id,
        segment_index,
        model_version
    )
);

CREATE INDEX idx_song_status
ON songs(status);

CREATE INDEX idx_embeddings_song
ON audio_embeddings(song_id);
