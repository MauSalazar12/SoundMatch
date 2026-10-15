import time
import statistics

import numpy as np

from src.recommendations.recommend import recommend_songs


def benchmark(
    song_id: int,
    repetitions: int = 100
):

    if repetitions < 1:
        raise ValueError(
            "repetitions debe ser positivo"
        )

    times = []

    # Calentamiento
    for _ in range(5):
        recommend_songs(song_id, top_k=5)

    for _ in range(repetitions):

        start = time.perf_counter()

        recommend_songs(
            song_id=song_id,
            top_k=5
        )

        elapsed_ms = (
            time.perf_counter() - start
        ) * 1000

        times.append(elapsed_ms)

    print(
        f"Latencia promedio: "
        f"{statistics.mean(times):.2f} ms"
    )

    print(
        f"Latencia P95: "
        f"{np.percentile(times, 95):.2f} ms"
    )


if __name__ == "__main__":

    benchmark(
        song_id=15,
        repetitions=100
    )