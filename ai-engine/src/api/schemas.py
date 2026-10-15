from pydantic import BaseModel, Field


class ErrorResponse(BaseModel):
    code: str
    message: str
    request_id: str


class Song(BaseModel):
    id: int
    title: str
    artist: str | None
    genre: str | None
    duration_seconds: float | None
    status: str


class SongPage(BaseModel):
    items: list[Song]
    page: int
    size: int
    total: int


class Recommendation(BaseModel):
    song_id: int
    title: str
    artist: str | None
    genre: str | None
    similarity: float = Field(ge=-1.000001, le=1.000001, allow_inf_nan=False)
    segment_index: int
    start_seconds: float
    end_seconds: float


class Recommendations(BaseModel):
    reference_song_id: int | None = None
    strategy: str
    recommendations: list[Recommendation]


class Health(BaseModel):
    status: str
    http: str = "up"
    model: str
    database: str
