"""Opt-in real HTTP tests. No mocked model, generated catalog or database writes."""
import os
from pathlib import Path
import httpx
import pytest
from src.core.config import AUDIO_DIR

pytestmark = [pytest.mark.integration,
              pytest.mark.skipif(os.getenv("RUN_STAGE3_INTEGRATION") != "1", reason="Set RUN_STAGE3_INTEGRATION=1 with both services running")]
BACKEND = os.getenv("BACKEND_URL", "http://localhost:8080")
ENGINE = os.getenv("AI_ENGINE_URL", "http://localhost:8000")
SONG_ID = int(os.getenv("TEST_SONG_ID", "2"))


def check_recommendations(payload, reference=None):
    recommendations = payload["recommendations"]
    assert 0 < len(recommendations) <= 5
    ids = [item["song_id"] for item in recommendations]
    assert len(ids) == len(set(ids))
    if reference:
        assert reference not in ids
    scores = [item["similarity"] for item in recommendations]
    assert scores == sorted(scores, reverse=True)
    assert all(-1.000001 <= score <= 1.000001 for score in scores)
    assert "embedding" not in str(payload)


def test_real_service_communication():
    with httpx.Client(timeout=110, trust_env=False) as client:
        assert client.get(ENGINE + "/health/ready").status_code == 200
        assert client.get(BACKEND + "/actuator/health/readiness").status_code == 200
        response = client.get(BACKEND + "/api/v1/songs?size=5")
        assert response.status_code == 200
        assert response.json()["total"] > 0
        filtered = client.get(BACKEND + "/api/v1/songs", params={"title": "Food", "artist": "AWOL"})
        assert filtered.status_code == 200
        assert all("food" in item["title"].lower() for item in filtered.json()["items"])
        direct = client.get(f"{ENGINE}/api/v1/recommendations/{SONG_ID}?limit=5")
        public = client.get(f"{BACKEND}/api/v1/recommendations/{SONG_ID}?limit=5")
        assert public.status_code == direct.status_code == 200
        assert public.json() == direct.json()
        check_recommendations(public.json(), SONG_ID)
        assert client.get(BACKEND + "/api/v1/songs/9223372036854775807").status_code == 404
        assert client.get(BACKEND + "/api/v1/recommendations/2?limit=0").status_code == 400
        assert client.get(BACKEND + "/v3/api-docs").status_code == 200


def test_real_audio_inference_via_spring_boot():
    path = Path(os.getenv("TEST_AUDIO_PATH", str(AUDIO_DIR / "000" / "000002.mp3")))
    assert path.is_file(), "Provide TEST_AUDIO_PATH for a representative 10–60 second music clip"
    with httpx.Client(timeout=110, trust_env=False) as client, path.open("rb") as audio:
        response = client.post(BACKEND + "/api/v1/recommendations/audio?limit=5",
                               files={"file": (path.name, audio, "audio/mpeg")})
    assert response.status_code == 200, response.text
    assert response.json()["strategy"] == "mean_query_best_match"
    check_recommendations(response.json())
