import logging

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError
from starlette.exceptions import HTTPException

logger = logging.getLogger("soundmatch")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def error_response(request: Request, status: int, code: str, message: str):
    return JSONResponse(status_code=status, content={
        "code": code, "message": message,
        "request_id": getattr(request.state, "request_id", "unknown"),
    })


def install_handlers(app):
    @app.exception_handler(ApiError)
    async def api_error(request, exc):
        return error_response(request, exc.status, exc.code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return error_response(request, 400, "INVALID_PARAMETERS", "Parámetros inválidos")

    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        return error_response(request, exc.status_code, "HTTP_ERROR", "Solicitud no válida")

    @app.exception_handler(SQLAlchemyError)
    async def database_error(request, exc):
        logger.error("database_unavailable", extra={"error_type": type(exc).__name__})
        return error_response(request, 503, "DATABASE_UNAVAILABLE", "Base de datos no disponible")

    @app.exception_handler(Exception)
    async def unexpected(request, exc):
        logger.error("unexpected_error", extra={"error_type": type(exc).__name__})
        return error_response(request, 500, "INTERNAL_ERROR", "Error interno")
