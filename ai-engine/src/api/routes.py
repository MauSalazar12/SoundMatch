from typing import Annotated
from fastapi import APIRouter, File, Path, Query, Request, UploadFile
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from src.api.errors import ApiError
from src.api.schemas import Health, Recommendations, Song, SongPage

router = APIRouter()
SongId = Annotated[int, Path(ge=1, le=9223372036854775807)]
Limit = Annotated[int, Query(ge=1, le=100)]
Filter = Annotated[str | None, Query(max_length=200)]


@router.get("/health", response_model=Health, tags=["health"])
def health(request: Request):
    try:
        database = "ready" if request.app.state.repository.health() else "unavailable"
    except Exception:
        database = "unavailable"
    model = request.app.state.inference.state
    return {"status": "ready" if model == database == "ready" else "degraded",
            "http": "up", "database": database, "model": model}


@router.get("/health/ready", response_model=Health, tags=["health"])
def ready(request: Request):
    result = health(request)
    return JSONResponse(result, status_code=200 if result["status"] == "ready" else 503)


@router.get("/api/v1/songs", response_model=SongPage, tags=["songs"])
def songs(request: Request, page: Annotated[int, Query(ge=0, le=1000000)] = 0,
          size: Annotated[int, Query(ge=1, le=100)] = 20,
          title: Filter = None, artist: Filter = None, genre: Filter = None):
    return request.app.state.repository.list(page, size, title, artist, genre)


@router.get("/api/v1/songs/{song_id}", response_model=Song, tags=["songs"])
def song(request: Request, song_id: SongId):
    return request.app.state.recommendations.song(song_id)


@router.post("/api/v1/recommendations/audio", response_model=Recommendations, tags=["recommendations"])
async def audio(request: Request, file: Annotated[UploadFile, File()], limit: Limit = 5):
    try:
        data = await file.read(request.app.state.settings.max_upload_bytes + 1)
    finally:
        await file.close()
    if not data:
        raise ApiError(400, "INVALID_AUDIO", "Archivo vacío")
    if len(data) > request.app.state.settings.max_upload_bytes:
        raise ApiError(413, "FILE_TOO_LARGE", "Archivo demasiado grande")
    embedding = await request.app.state.inference.encode(data)
    return await run_in_threadpool(request.app.state.recommendations.by_embedding, embedding, limit)


@router.get("/api/v1/recommendations/{song_id}", response_model=Recommendations, tags=["recommendations"])
def recommendations(request: Request, song_id: SongId, limit: Limit = 5):
    return request.app.state.recommendations.by_id(song_id, limit)
