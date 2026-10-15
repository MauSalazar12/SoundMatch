from sqlalchemy import text

from src.core.database import engine
from src.core.config import MODEL_VERSION
from src.embeddings.vectors import to_vector_literal

def find_similar_songs(
    song_id: int,
    segment_index: int = 0,
    candidate_limit: int = 100
) -> list[dict]:

    if candidate_limit < 1 or candidate_limit > 1000:
        raise ValueError(
            "candidate_limit debe estar entre 1 y 1000"
        )

    reference = """
            SELECT
                id,
                song_id,
                embedding,
                model_version
            FROM audio_embeddings
            WHERE song_id = :song_id
              AND segment_index = :segment_index
              AND embedding IS NOT NULL
              AND model_version = :model_version
            LIMIT 1
    """
    return _search(reference, {
        "song_id": song_id,
        "segment_index": segment_index,
        "model_version": MODEL_VERSION,
        "candidate_limit": candidate_limit,
    })


def find_similar_embedding(embedding, candidate_limit: int = 100) -> list[dict]:
    """Use exactly the stage-2 candidate search for a newly encoded query."""
    if not 1 <= candidate_limit <= 1000:
        raise ValueError("candidate_limit debe estar entre 1 y 1000")
    return _search(
        "SELECT CAST(:embedding AS vector) AS embedding, "
        "CAST(NULL AS bigint) AS song_id, :model_version AS model_version",
        {"embedding": to_vector_literal(embedding),
         "model_version": MODEL_VERSION, "candidate_limit": candidate_limit},
    )


def _search(reference: str, params: dict) -> list[dict]:
    # reference is internal SQL; all caller-supplied values remain bound parameters.
    query = "WITH reference AS (" + reference + ")" + """
        SELECT
            ae.id,
            ae.song_id,
            s.title,
            s.artist,
            s.genre,
            ae.segment_index,
            ae.start_seconds,
            ae.end_seconds,
            1 - (ae.embedding <=> r.embedding)
                AS similarity
        FROM audio_embeddings ae
        CROSS JOIN reference r
        JOIN songs s
            ON s.id = ae.song_id
        WHERE (r.song_id IS NULL OR ae.song_id <> r.song_id)
          AND ae.embedding IS NOT NULL
          AND ae.model_version = r.model_version
        ORDER BY ae.embedding <=> r.embedding
        LIMIT :candidate_limit
    """

    with engine.connect() as conn:
        rows = conn.execute(
            text(query),
            params,
        ).fetchall()

    return [
        {
            "embedding_id": row[0],
            "song_id": row[1],
            "title": row[2],
            "artist": row[3],
            "genre": row[4],
            "segment_index": row[5],
            "start_seconds": row[6],
            "end_seconds": row[7],
            "similarity": float(row[8])
        }
        for row in rows
    ]
