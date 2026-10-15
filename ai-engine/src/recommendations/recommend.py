import argparse

from sqlalchemy import text

from src.core.database import engine

from src.core.config import MODEL_VERSION
from src.recommendations.similarity import find_similar_songs as find_similar_segments
from src.recommendations.ranking import rank_songs


def recommend(song_id, top_k=5):
    if top_k < 1:
        raise ValueError("top_k debe ser mayor que cero")

    query = text("""
        WITH reference AS (
            SELECT embedding
            FROM audio_embeddings
            WHERE song_id = :song_id
              AND segment_index = -1
              AND model_version = :model_version
        )
        SELECT
            s.id,
            s.title,
            s.artist,
            s.genre,
            1 - (e.embedding <=> r.embedding)
                AS similarity
        FROM audio_embeddings e
        JOIN songs s ON s.id = e.song_id
        CROSS JOIN reference r
        WHERE e.segment_index = -1
          AND e.song_id <> :song_id
          AND e.model_version = :model_version
        ORDER BY e.embedding <=> r.embedding
        LIMIT :top_k
    """)

    with engine.connect() as connection:
        reference_exists = connection.execute(
            text("""
                SELECT EXISTS (
                    SELECT 1
                    FROM audio_embeddings
                    WHERE song_id = :song_id
                      AND segment_index = -1
                      AND model_version = :model_version
                )
            """),
            {
                "song_id": song_id,
                "model_version": MODEL_VERSION,
            },
        ).scalar_one()

        if not reference_exists:
            raise ValueError(
                f"La canción {song_id} no tiene un embedding "
                f"global para {MODEL_VERSION}"
            )

        results = connection.execute(
            query,
            {
                "song_id": song_id,
                "model_version": MODEL_VERSION,
                "top_k": top_k,
            },
        ).mappings().all()

    return [dict(row) for row in results]


def recommend_songs(
    song_id: int,
    segment_index: int = 0,
    top_k: int = 5,
    candidate_limit: int = 100,
) -> list[dict]:
    if not 1 <= top_k <= 100:
        raise ValueError("top_k debe estar entre 1 y 100")

    if not top_k <= candidate_limit <= 1000:
        raise ValueError("candidate_limit debe estar entre top_k y 1000")

    segments = find_similar_segments(
        song_id=song_id,
        segment_index=segment_index,
        candidate_limit=candidate_limit,
    )
    return rank_songs(segments=segments, top_k=top_k)


def main():
    parser = argparse.ArgumentParser(
        description="soundMatch - Recommendations"
    )
    parser.add_argument("--song-id", type=int, required=True)
    parser.add_argument("--segment-index", type=int, default=0)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--candidate-limit", type=int, default=100)
    args = parser.parse_args()

    try:
        results = recommend_songs(
            song_id=args.song_id,
            segment_index=args.segment_index,
            top_k=args.top_k,
            candidate_limit=args.candidate_limit,
        )
    except ValueError as error:
        parser.error(str(error))

    if not results:
        print("No se encontraron recomendaciones.")
        return

    print("\nCanciones recomendadas:\n")
    for index, song in enumerate(results, 1):
        print(f"{index}. {song['title']} - {song['artist']}")
        print(f"   Similitud: {song['similarity']:.4f}")
        print(
            f"   Segmento: {song['start_seconds']:.1f}s - "
            f"{song['end_seconds']:.1f}s"
        )


if __name__ == "__main__":
    main()
