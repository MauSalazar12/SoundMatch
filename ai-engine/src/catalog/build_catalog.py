
import pandas as pd

from src.core.config import DATA_DIR, AUDIO_DIR, METADATA, OUTPUT, PROJECT_ROOT


def get_audio_path(track_id):
    track_id = f"{int(track_id):06d}"

    return (
        AUDIO_DIR
        / track_id[:3]
        / f"{track_id}.mp3"
    )


def build_catalog(limit=100):
    tracks = pd.read_csv(
        METADATA,
        header=[0, 1],
        index_col=0,
        low_memory=False
    )

    # Seleccionar solamente FMA Small.
    tracks = tracks[
        tracks[("set", "subset")] == "small"
    ]

    records = []

    for track_id, row in tracks.iterrows():
        audio_path = get_audio_path(track_id)

        if not audio_path.exists():
            continue

        records.append({
            "track_id": int(track_id),
            "title": row[("track", "title")],
            "artist": row[("artist", "name")],
            "genre": row[("track", "genre_top")],
            "audio_path": str(audio_path.relative_to(PROJECT_ROOT)),
            "status": "pending"
        })

        if len(records) >= limit:
            break

    catalog = pd.DataFrame(records)

    OUTPUT.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    catalog.to_csv(OUTPUT, index=False)

    print(f"Catalogadas: {len(catalog)} canciones")


if __name__ == "__main__":
    build_catalog(limit=100)
