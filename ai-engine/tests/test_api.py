import io
import time
from unittest.mock import Mock

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient
from sqlalchemy.exc import OperationalError

from src.api.app import create_app
from src.api.errors import ApiError
from src.api.settings import Settings
from src.services.inference import decode_segments

SONG = {"id": 2, "title": "Food", "artist": "AWOL", "genre": "Hip-Hop",
        "duration_seconds": 30.0, "status": "processed", "embedding": [99], "audio_path": "private"}
REC = {"song_id": 5, "title": "This World", "artist": "AWOL", "genre": "Hip-Hop",
       "similarity": .9, "segment_index": 0, "start_seconds": 0, "end_seconds": 10}


def wav(seconds=10, silence=False):
    buffer = io.BytesIO()
    samples = np.zeros(int(8000 * seconds)) if silence else .1 * np.sin(np.arange(int(8000 * seconds)) * .2)
    sf.write(buffer, samples, 8000, format="WAV")
    return buffer.getvalue()


@pytest.fixture
def api():
    repository = Mock()
    repository.health.return_value = True
    repository.list.return_value = {"items": [SONG], "page": 0, "size": 20, "total": 1}
    service = Mock()
    service.song.return_value = SONG
    service.by_id.return_value = {"reference_song_id": 2, "strategy": "segment_0_best_match", "recommendations": [REC]}
    service.by_embedding.return_value = {"strategy": "mean_query_best_match", "recommendations": [REC]}
    encoder = Mock()
    encoder.encode.return_value = np.ones(512, dtype=np.float32) / np.sqrt(512)
    factory = Mock(return_value=encoder)
    app = create_app(Settings(max_upload_bytes=400000, inference_timeout_seconds=300), repository, service, factory)
    with TestClient(app, raise_server_exceptions=False) as client:
        for _ in range(100):
            if app.state.inference.state != "loading":
                break
            time.sleep(.01)
        yield client, app, repository, service, factory, encoder


def test_health_and_readiness(api):
    client, app, repository, *_ = api
    assert client.get("/health").json() == {"status": "ready", "http": "up", "model": "ready", "database": "ready"}
    repository.health.side_effect = RuntimeError("secret")
    assert client.get("/health").status_code == 200
    assert client.get("/health/ready").status_code == 503
    app.state.inference.state = "failed"
    assert client.get("/health").json()["model"] == "failed"


def test_catalog_excludes_private_fields(api):
    client, _, repository, *_ = api
    response = client.get("/api/v1/songs?title=Food&artist=AWOL&genre=Hip-Hop")
    assert response.status_code == 200
    assert set(response.json()) == {"items", "page", "size", "total"}
    assert set(response.json()["items"][0]) == {"id", "title", "artist", "genre", "duration_seconds", "status"}
    repository.list.assert_called_once_with(0, 20, "Food", "AWOL", "Hip-Hop")
    assert client.get("/api/v1/songs/2").json()["title"] == "Food"


@pytest.mark.parametrize("path", ["/api/v1/songs/0", "/api/v1/songs/no", "/api/v1/songs/9223372036854775808",
    "/api/v1/songs?page=-1", "/api/v1/songs?size=101", "/api/v1/songs?title=" + "a" * 201,
    "/api/v1/recommendations/2?limit=0", "/api/v1/recommendations/2?limit=101"])
def test_invalid_parameters(api, path):
    response = api[0].get(path)
    assert response.status_code == 400
    assert set(response.json()) == {"code", "message", "request_id"}
    assert response.headers["x-request-id"] == response.json()["request_id"]


def test_recommendation_contract_and_not_found(api):
    client, _, _, service, *_ = api
    response = client.get("/api/v1/recommendations/2?limit=5")
    assert response.status_code == 200
    assert response.json()["recommendations"][0]["song_id"] == 5
    service.by_id.assert_called_once_with(2, 5)
    service.by_id.side_effect = ApiError(404, "SONG_NOT_FOUND", "Canción inexistente")
    assert client.get("/api/v1/recommendations/999").status_code == 404
    service.song.side_effect = ApiError(404, "SONG_NOT_FOUND", "Canción inexistente")
    assert client.get("/api/v1/songs/999").status_code == 404


def test_audio_uses_one_model_and_normalized_embedding(api):
    client, _, _, service, factory, encoder = api
    for _ in range(2):
        response = client.post("/api/v1/recommendations/audio", files={"file": ("wrong.txt", wav(), "text/plain")})
        assert response.status_code == 200, response.text
    factory.assert_called_once()
    assert encoder.encode.call_count == 2
    vector = service.by_embedding.call_args.args[0]
    assert vector.shape == (512,)
    assert np.linalg.norm(vector) == pytest.approx(1)


@pytest.mark.parametrize("data,status", [(b"", 400), (b"not audio", 415), (wav(1), 400), (wav(10, True), 400), (b"x" * 400001, 413)],
                         ids=["empty", "fake", "short", "silent", "oversized"])
def test_bad_audio(api, data, status):
    response = api[0].post("/api/v1/recommendations/audio", files={"file": ("music.wav", data, "audio/wav")})
    assert response.status_code == status, response.text
    assert set(response.json()) == {"code", "message", "request_id"}


def test_missing_file_and_multipart_limit(api):
    assert api[0].post("/api/v1/recommendations/audio").status_code == 400
    assert api[0].post("/api/v1/recommendations/audio", content=b"x" * 500000).status_code == 413


def test_chunked_body_limit(api):
    response = api[0].post("/api/v1/recommendations/audio", content=(b"x" * 100000 for _ in range(5)))
    assert response.status_code == 413


def test_inference_failures_hide_details(api):
    client, app, _, _, _, encoder = api
    encoder.encode.side_effect = RuntimeError("private path and credentials")
    response = client.post("/api/v1/recommendations/audio", files={"file": ("test.wav", wav())})
    assert response.status_code == 500
    assert "private" not in response.text
    app.state.inference.state = "failed"
    assert client.post("/api/v1/recommendations/audio", files={"file": ("test.wav", wav())}).status_code == 503


def test_database_failure(api):
    api[2].list.side_effect = OperationalError("SELECT", {}, Exception("password"))
    response = api[0].get("/api/v1/songs")
    assert response.status_code == 503
    assert "password" not in response.text


def test_openapi(api):
    spec = api[0].get("/openapi.json").json()
    assert "/api/v1/recommendations/audio" in spec["paths"]
    assert "413" in spec["paths"]["/api/v1/recommendations/audio"]["post"]["responses"]


def test_audio_duration_bound():
    with pytest.raises(ApiError) as error:
        decode_segments(wav(11), 10)
    assert error.value.status == 400
