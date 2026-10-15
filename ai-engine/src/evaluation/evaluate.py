from sqlalchemy import text
from src.core.database import engine
from src.recommendations.recommend import recommend_songs


def get_song_genre(song_id: int):

    with engine.connect() as conn:
        row = conn.execute(
            text("SELECT genre FROM songs WHERE id = :song_id"),
            {"song_id": song_id},
        ).fetchone()

    return row[0] if row else None


def precision_at_k(
    song_id: int,
    k: int = 5
) -> float:

    if k < 1:
        raise ValueError("k debe ser positivo")

    reference_genre = get_song_genre(song_id)

    if not reference_genre:
        raise ValueError(
            f"La canción {song_id} no tiene género"
        )

    recommendations = recommend_songs(
        song_id=song_id,
        top_k=k
    )

    relevant = sum(
        1
        for song in recommendations
        if song["genre"] == reference_genre
    )

    return relevant / k
