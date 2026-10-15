def rank_songs(
    segments: list[dict],
    top_k: int = 5
) -> list[dict]:

    if top_k < 1:
        raise ValueError("top_k debe ser positivo")

    best_by_song = {}

    for segment in segments:

        song_id = segment["song_id"]

        current = best_by_song.get(song_id)

        if (
            current is None
            or segment["similarity"] > current["similarity"]
        ):
            best_by_song[song_id] = segment.copy()

    ranked = sorted(
        best_by_song.values(),
        key=lambda song: song["similarity"],
        reverse=True
    )

    return ranked[:top_k]