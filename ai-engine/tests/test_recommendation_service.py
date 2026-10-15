from unittest.mock import Mock, patch
import numpy as np
import pytest
from src.api.errors import ApiError
from src.services.recommendations import RecommendationService


def test_id_reuses_stage_two_and_checks_reference():
    repository = Mock()
    service = RecommendationService(repository, 1000)
    with patch("src.services.recommendations.recommend_songs", return_value=[]) as existing:
        service.by_id(2, 5)
        existing.assert_called_once_with(2, top_k=5, candidate_limit=1000)
        repository.get.return_value = None
        with pytest.raises(ApiError) as error:
            service.by_id(999, 5)
        assert error.value.status == 404
        repository.get.return_value = {"id": 2}
        repository.has_embedding.return_value = False
        with pytest.raises(ApiError) as error:
            service.by_id(2, 5)
        assert error.value.code == "EMBEDDING_UNAVAILABLE"


def test_audio_ranking_uses_best_segment_without_duplicates():
    service = RecommendationService(Mock(), 1000)
    candidates = [{"song_id": 5, "similarity": .8}, {"song_id": 5, "similarity": .95},
                  {"song_id": 7, "similarity": .9}]
    with patch("src.services.recommendations.find_similar_embedding", return_value=candidates):
        result = service.by_embedding(np.ones(512), 2)
    assert result["recommendations"] == [candidates[1], candidates[2]]
