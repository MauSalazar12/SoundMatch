import logging
import time
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header, Query, Request
from starlette.concurrency import run_in_threadpool

from src.api.errors import ApiError
from src.api.schemas import Recommendations

router = APIRouter()
logger = logging.getLogger("soundmatch")


@router.post("/internal/v1/recommendations/pcm", response_model=Recommendations, tags=["internal"])
async def pcm(request: Request,
              sample_rate: Annotated[int, Header(alias="X-Sample-Rate")],
              channels: Annotated[int, Header(alias="X-Audio-Channels")],
              sequence: Annotated[int, Header(alias="X-Sequence", ge=1)],
              session_id: Annotated[UUID | None, Header(alias="X-Session-Id")] = None,
              limit: Annotated[int, Query(ge=1, le=100)] = 5):
    settings = request.app.state.settings
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/octet-stream":
        raise ApiError(415, "UNSUPPORTED_AUDIO", "Se requiere application/octet-stream")
    if sample_rate not in settings.pcm_sample_rates or channels != 1:
        raise ApiError(400, "INVALID_PARAMETERS", "Frecuencia o canales PCM no compatibles")
    data = await request.body()  # Middleware has already enforced the PCM-specific bound, including chunked bodies.
    context = {"request_id": request.state.request_id, "session_id": str(session_id) if session_id else None,
               "sequence": sequence, "sample_rate": sample_rate}
    started = time.perf_counter()
    try:
        embedding = await request.app.state.inference.encode_pcm(data, sample_rate, channels, context)
        search_started = time.perf_counter()
        try:
            return await run_in_threadpool(request.app.state.recommendations.by_embedding, embedding, limit)
        finally:
            logger.info("pcm_search", extra={**context,
                "search_ms": round((time.perf_counter() - search_started) * 1000, 2)})
    except ApiError as exc:
        logger.warning("pcm_error", extra={**context, "error_type": exc.code})
        raise
    except Exception as exc:
        logger.error("pcm_error", extra={**context, "error_type": type(exc).__name__})
        raise
    finally:
        logger.info("pcm_request", extra={**context, "duration_ms": round((time.perf_counter() - started) * 1000, 2)})
