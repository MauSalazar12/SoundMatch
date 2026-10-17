import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from src.api.errors import install_handlers
from src.api.middleware import JsonFormatter, RequestMiddleware
from src.api.routes import router
from src.api.pcm_routes import router as pcm_router
from src.api.schemas import ErrorResponse
from src.api.settings import Settings
from src.services.inference import InferenceService, create_encoder


def create_app(settings=None, repository=None, recommendation_service=None, encoder_factory=create_encoder):
    settings = settings or Settings.from_env()
    if repository is None:
        from src.persistence.catalog import CatalogRepository
        repository = CatalogRepository()
    if recommendation_service is None:
        from src.services.recommendations import RecommendationService
        recommendation_service = RecommendationService(repository, settings.candidate_limit)

    @asynccontextmanager
    async def lifespan(app):
        inference = InferenceService(settings, encoder_factory)
        app.state.inference = inference
        loading = asyncio.wrap_future(inference.executor.submit(inference.load))
        try:
            yield
        finally:
            await loading
            await asyncio.to_thread(inference.close)

    app = FastAPI(title="SoundMatch AI Engine", version="3.0.0", lifespan=lifespan,
                  responses={code: {"model": ErrorResponse} for code in (400, 404, 413, 415, 500, 503, 504)})
    app.state.settings = settings
    app.state.repository = repository
    app.state.recommendations = recommendation_service
    app.add_middleware(RequestMiddleware, max_body_bytes=settings.max_upload_bytes + 65536,
                       max_pcm_bytes=settings.max_pcm_bytes, internal_api_token=settings.internal_api_token)
    install_handlers(app)
    app.include_router(router)
    app.include_router(pcm_router)
    logger = logging.getLogger("soundmatch")
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(JsonFormatter())
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        logger.propagate = False
    return app
