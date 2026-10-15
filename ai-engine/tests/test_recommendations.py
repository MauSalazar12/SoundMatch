from src.recommendations.ranking import rank_songs


def test_song_deduplication():

    segments = [
        {
            "song_id": 1,
            "similarity": 0.80,
            "segment_index": 0
        },
        {
            "song_id": 1,
            "similarity": 0.95,
            "segment_index": 1
        },
        {
            "song_id": 2,
            "similarity": 0.90,
            "segment_index": 0
        }
    ]

    results = rank_songs(
        segments,
        top_k=2
    )

    assert len(results) == 2

    assert results[0]["song_id"] == 1
    assert results[0]["similarity"] == 0.95
    assert results[0]["segment_index"] == 1

    assert results[1]["song_id"] == 2


def test_empty_results():

    results = rank_songs([], top_k=5)

    assert results == []


def test_top_k():

    segments = [
        {
            "song_id": i,
            "similarity": 1 - i / 100
        }
        for i in range(1, 11)
    ]

    results = rank_songs(
        segments,
        top_k=5
    )

    assert len(results) == 5