CREATE INDEX IF NOT EXISTS
    idx_audio_embeddings_hnsw
ON audio_embeddings
USING hnsw (
    embedding vector_cosine_ops
)
WITH (
    m = 16,
    ef_construction = 64
);

CREATE INDEX IF NOT EXISTS
    idx_audio_embeddings_song_segment
ON audio_embeddings (
    song_id,
    segment_index
);
CREATE INDEX IF NOT EXISTS
    idx_audio_embeddings_model_version
ON audio_embeddings (
    model_version
);