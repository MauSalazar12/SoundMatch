"""Opt-in tests with the unchanged local CLAP checkpoint and existing read-only catalog."""
import asyncio
import os

import numpy as np
import pytest

from src.api.settings import Settings
from src.services.inference import InferenceService

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def real_inference():
    service = InferenceService(Settings.from_env())
    service.load()
    try:
        assert service.state == "ready", "CLAP checkpoint and cached RoBERTa assets must be available"
        yield service
    finally:
        service.close()


def samples():
    return (.1 * np.sin(np.arange(480000) * (440 * 2 * np.pi / 48000))).astype("<f4").tobytes()


@pytest.mark.skipif(os.getenv("RUN_STAGE4_MODEL") != "1", reason="Set RUN_STAGE4_MODEL=1 for real CLAP inference")
def test_real_pcm_clap_embedding(real_inference):
    encoder = real_inference.encoder
    vector = asyncio.run(real_inference.encode_pcm(samples(), 48000, 1))
    assert vector.shape == (512,)
    assert np.isfinite(vector).all()
    assert np.linalg.norm(vector) == pytest.approx(1, abs=1e-5)
    assert real_inference.encoder is encoder


@pytest.mark.skipif(os.getenv("RUN_STAGE4_DATABASE") != "1", reason="Set RUN_STAGE4_DATABASE=1 for real CLAP + pgvector")
def test_real_pcm_catalog_recommendations(real_inference):
    from src.persistence.catalog import CatalogRepository
    from src.services.recommendations import RecommendationService
    repository = CatalogRepository()
    assert repository.health()
    vector = asyncio.run(real_inference.encode_pcm(samples(), 48000, 1))
    result = RecommendationService(repository, 1000).by_embedding(vector, 5)
    rows = result["recommendations"]
    assert 0 < len(rows) <= 5
    assert len({row["song_id"] for row in rows}) == len(rows)
    assert all(np.isfinite(row["similarity"]) for row in rows)
    assert [row["similarity"] for row in rows] == sorted((row["similarity"] for row in rows), reverse=True)
