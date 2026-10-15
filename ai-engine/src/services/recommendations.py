from src.api.errors import ApiError
from src.recommendations.recommend import recommend_songs
from src.recommendations.similarity import find_similar_embedding
from src.recommendations.ranking import rank_songs


class RecommendationService:
    def __init__(self, repository, candidate_limit: int):
        self.repository, self.candidate_limit = repository, candidate_limit

    def song(self, song_id: int):
        song = self.repository.get(song_id)
        if song is None:
            raise ApiError(404, "SONG_NOT_FOUND", "Canción inexistente")
        return song

    def by_id(self, song_id: int, limit: int):
        self.song(song_id)
        if not self.repository.has_embedding(song_id):
            raise ApiError(503, "EMBEDDING_UNAVAILABLE", "Embedding de referencia no disponible")
        return {"reference_song_id": song_id, "strategy": "segment_0_best_match",
                "recommendations": recommend_songs(song_id, top_k=limit,
                                                    candidate_limit=self.candidate_limit)}

    def by_embedding(self, embedding, limit: int):
        candidates = find_similar_embedding(embedding, candidate_limit=self.candidate_limit)
        return {"reference_song_id": None, "strategy": "mean_query_best_match",
                "recommendations": rank_songs(candidates, top_k=limit)}
