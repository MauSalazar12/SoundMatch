import numpy as np
from sqlalchemy import text

from src.core.database import engine
from src.core.config import resolve_project_path
from src.audio.audio_processing import (
    SAMPLE_RATE,
    load_audio,
    split_audio,
)
from src.embeddings.clap_encoder import CLAPEncoder, MODEL_VERSION
from src.embeddings.vectors import normalize, to_vector_literal


def get_pending_songs(limit=100):
    query = text("""
        SELECT id, audio_path
        FROM songs
        WHERE status IN ('pending', 'failed')
        ORDER BY id
        LIMIT :limit
    """)

    with engine.connect() as connection:
        return connection.execute(
            query,
            {"limit": limit},
        ).mappings().all()


def save_embeddings(
    song_id,
    segments,
    vectors,
    duration_seconds,
):
    song_vector = normalize(
        np.mean(vectors, axis=0)
    ).astype(np.float32)

    insert_query = text("""
        INSERT INTO audio_embeddings (
            song_id,
            segment_index,
            start_seconds,
            end_seconds,
            model_version,
            embedding
        )
        VALUES (
            :song_id,
            :segment_index,
            :start_seconds,
            :end_seconds,
            :model_version,
            CAST(:embedding AS vector)
        )
    """)

    records = []

    for segment, vector in zip(segments, vectors):
        records.append({
            "song_id": song_id,
            "segment_index": segment["index"],
            "start_seconds": segment["start_seconds"],
            "end_seconds": segment["end_seconds"],
            "model_version": MODEL_VERSION,
            "embedding": to_vector_literal(vector),
        })

    records.append({
        "song_id": song_id,
        "segment_index": -1,
        "start_seconds": 0.0,
        "end_seconds": duration_seconds,
        "model_version": MODEL_VERSION,
        "embedding": to_vector_literal(song_vector),
    })

    with engine.begin() as connection:
        connection.execute(
            text("""
                DELETE FROM audio_embeddings
                WHERE song_id = :song_id
                  AND model_version = :model_version
            """),
            {
                "song_id": song_id,
                "model_version": MODEL_VERSION,
            },
        )

        connection.execute(insert_query, records)

        connection.execute(
            text("""
                UPDATE songs
                SET status = 'processed',
                    processed_at = NOW(),
                    duration_seconds = :duration_seconds
                WHERE id = :song_id
            """),
            {
                "song_id": song_id,
                "duration_seconds": duration_seconds,
            },
        )


def process_catalog():
    songs = get_pending_songs(limit=100)

    if not songs:
        print("No hay canciones pendientes")
        return

    encoder = CLAPEncoder()

    for song in songs:
        song_id = song["id"]

        print(f"Procesando canción {song_id}")

        try:
            audio = load_audio(resolve_project_path(song["audio_path"]))
            segments = split_audio(audio)

            if not segments:
                raise ValueError("Sin fragmentos válidos")

            vectors = np.stack([
                encoder.encode(segment["audio"])
                for segment in segments
            ])

            duration_seconds = len(audio) / SAMPLE_RATE

            save_embeddings(
                song_id,
                segments,
                vectors,
                duration_seconds,
            )

            print(f"OK: {song_id}")

        except Exception as error:
            print(f"ERROR {song_id}: {error}")

            with engine.begin() as connection:
                connection.execute(
                    text("""
                        UPDATE songs
                        SET status = 'failed'
                        WHERE id = :song_id
                    """),
                    {"song_id": song_id},
                )


if __name__ == "__main__":
    process_catalog()
