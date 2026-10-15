import pandas as pd
from sqlalchemy import text

from src.core.config import DATA_DIR, AUDIO_DIR, METADATA, PROJECT_ROOT
from src.core.database import engine


def get_audio_path(track_id):
    track_id = f"{int(track_id):06d}"

    return (
        AUDIO_DIR
        / track_id[:3]
        / f"{track_id}.mp3"
    )


def import_songs(limit=10):
    tracks = pd.read_csv(
        METADATA,
        header=[0, 1],
        index_col=0,
        low_memory=False
    )

    tracks = tracks[
        tracks[("set", "subset")] == "small"
    ]

    inserted = 0

    query = text("""
        INSERT INTO songs (
            id,
            title,
            artist,
            genre,
            audio_path,
            status
        )
        VALUES (
            :id,
            :title,
            :artist,
            :genre,
            :audio_path,
            'pending'
        )
        ON CONFLICT (id) DO UPDATE SET
            title = EXCLUDED.title,
            artist = EXCLUDED.artist,
            genre = EXCLUDED.genre,
            audio_path = EXCLUDED.audio_path
    """)

    with engine.begin() as connection:

        for track_id, row in tracks.iterrows():

            path = get_audio_path(track_id)

            if not path.is_file():
                continue

            title = row[("track", "title")]
            artist = row[("artist", "name")]
            genre = row[("track", "genre_top")]

            connection.execute(
                query,
                {
                    "id": int(track_id),
                    "title": (
                        str(title)
                        if pd.notna(title)
                        else "Unknown"
                    ),
                    "artist": (
                        str(artist)
                        if pd.notna(artist)
                        else None
                    ),
                    "genre": (
                        str(genre)
                        if pd.notna(genre)
                        else None
                    ),
                    "audio_path": str(path.relative_to(PROJECT_ROOT))
                }
            )

            inserted += 1

            if limit is not None and inserted >= limit:
                break

    print(f"Canciones importadas: {inserted}")


if __name__ == "__main__":
    import_songs(limit=10)
