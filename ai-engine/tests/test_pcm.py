import asyncio
import threading
import time
from unittest.mock import Mock
from uuid import uuid4

import numpy as np
import pytest
from fastapi.testclient import TestClient
from sqlalchemy.exc import OperationalError

from src.api.app import create_app
from src.api.errors import ApiError
from src.api.settings import Settings
from src.audio.pcm import decode_pcm
from src.services.inference import InferenceService

PATH = "/internal/v1/recommendations/pcm"


def pcm(rate=48000):
    return (.1 * np.sin(np.arange(rate * 10) * (440 * 2 * np.pi / rate))).astype("<f4").tobytes()


def headers(rate=48000):
    return {"Content-Type": "application/octet-stream", "X-Sample-Rate": str(rate),
            "X-Audio-Channels": "1", "X-Sequence": "1", "X-Internal-Token": "test-token"}


@pytest.fixture
def api():
    encoder = Mock()
    encoder.encode.return_value = np.arange(512, dtype=np.float32)
    factory = Mock(return_value=encoder)
    service = Mock()
    service.by_embedding.return_value = {"strategy": "mean_query_best_match", "recommendations": [
        {"song_id": 123, "title": "Song", "artist": "Artist", "genre": None, "similarity": .89,
         "segment_index": 0, "start_seconds": 0, "end_seconds": 10}]}
    app = create_app(Settings(internal_api_token="test-token", inference_timeout_seconds=300), Mock(), service, factory)
    with TestClient(app, raise_server_exceptions=False) as client:
        for _ in range(100):
            if app.state.inference.state != "loading":
                break
            time.sleep(.01)
        yield client, service, encoder, factory, app


@pytest.mark.parametrize("rate", [44100, 48000])
def test_pcm_resample_encoder_reuse_vector_search_and_response(api, rate):
    client, service, encoder, factory, _ = api
    request_id = str(uuid4())
    for _ in range(2):
        response = client.post(PATH, content=pcm(rate), headers={**headers(rate), "X-Request-Id": request_id})
        assert response.status_code == 200, response.text
        assert response.headers["x-request-id"] == request_id
        assert response.json()["recommendations"][0]["song_id"] == 123
    factory.assert_called_once()
    assert encoder.encode.call_count == 2
    samples = encoder.encode.call_args.args[0]
    assert samples.shape == (480000,)
    assert samples.dtype == np.float32
    # Resampling must preserve the 440 Hz tone, not just its array length.
    peak = np.argmax(np.abs(np.fft.rfft(samples))) / 10
    assert peak == pytest.approx(440, abs=.1)
    vector, limit = service.by_embedding.call_args.args
    assert vector.shape == (512,)
    assert np.linalg.norm(vector) == pytest.approx(1)
    assert limit == 5


@pytest.mark.parametrize("data,status", [(b"", 400), (b"x", 400), (b"abcd", 400),
    (b"\0" * 1920000, 400), (b"x" * 1920004, 413)], ids=["empty", "unaligned", "short", "silent", "oversized"])
def test_invalid_lengths_silence_and_limits(api, data, status):
    response = api[0].post(PATH, content=data, headers=headers())
    assert response.status_code == status, response.text
    api[2].encode.assert_not_called()
    api[1].by_embedding.assert_not_called()


@pytest.mark.parametrize("sample", [np.nan, np.inf, -np.inf, 1.01, -1.01])
def test_invalid_samples(api, sample):
    data = np.frombuffer(pcm(), dtype="<f4").copy()
    data[42] = sample
    assert api[0].post(PATH, content=data.tobytes(), headers=headers()).status_code == 400
    api[2].encode.assert_not_called()


@pytest.mark.parametrize("key,value,status", [("X-Sample-Rate", "8000", 400),
    ("X-Audio-Channels", "2", 400), ("X-Sequence", "0", 400), ("X-Sequence", "abc", 400),
    ("X-Session-Id", "bad", 400), ("Content-Type", "audio/wav", 415), ("X-Internal-Token", "bad", 401)])
def test_invalid_headers(api, key, value, status):
    assert api[0].post(PATH, content=pcm(), headers={**headers(), key: value}).status_code == status
    api[2].encode.assert_not_called()


def test_missing_header_and_chunked_limit(api):
    missing = headers()
    del missing["X-Sample-Rate"]
    assert api[0].post(PATH, content=b"abcd", headers=missing).status_code == 400
    assert api[0].post(PATH, content=(b"x" * 100000 for _ in range(20)), headers=headers()).status_code == 413
    api[2].encode.assert_not_called()


@pytest.mark.parametrize("vector", [np.ones(511), np.zeros(512), np.full(512, np.nan)])
def test_invalid_embeddings(api, vector):
    api[2].encode.return_value = vector
    assert api[0].post(PATH, content=pcm(), headers=headers()).status_code == 500
    api[1].by_embedding.assert_not_called()


def test_model_and_database_failures(api):
    client, service, encoder, _, app = api
    encoder.encode.side_effect = RuntimeError("private model path")
    response = client.post(PATH, content=pcm(), headers=headers())
    assert response.status_code == 500 and "private" not in response.text
    encoder.encode.side_effect = None
    service.by_embedding.side_effect = OperationalError("SELECT", {}, Exception("password"))
    response = client.post(PATH, content=pcm(), headers=headers())
    assert response.status_code == 503 and "password" not in response.text
    app.state.inference.state = "failed"
    assert client.post(PATH, content=pcm(), headers=headers()).status_code == 503


def test_shared_slot_pcm_timeout_and_rest_busy():
    release, entered = threading.Event(), threading.Event()
    encoder = Mock()

    def slow(_):
        entered.set()
        release.wait(5)
        return np.ones(512)

    encoder.encode.side_effect = slow
    service = InferenceService(Settings(inference_timeout_seconds=.1), lambda _: encoder)
    service.load()

    async def scenario():
        try:
            with pytest.raises(ApiError) as error:
                await service.encode_pcm(pcm(), 48000, 1)
            assert error.value.status == 504 and entered.is_set()
            with pytest.raises(ApiError) as busy:
                await service.encode(b"file")
            assert busy.value.code == "INFERENCE_BUSY"
        finally:
            release.set()
            await asyncio.to_thread(service.close)
    asyncio.run(scenario())


def test_little_endian_samples():
    data = np.full(480000, .5, dtype="<f4").tobytes()
    assert data[:4] == b"\x00\x00\x00\x3f"
    np.testing.assert_array_equal(decode_pcm(data, 48000, 1, Settings()), np.full(480000, .5))
