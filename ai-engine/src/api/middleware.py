"""Bound multipart bodies before parsing, including chunked uploads."""
import json
import logging
import time
from uuid import uuid4

from starlette.responses import JSONResponse


class JsonFormatter(logging.Formatter):
    def format(self, record):
        data = {"level": record.levelname, "event": record.getMessage()}
        for key in ("request_id", "method", "status", "duration_ms", "error_type"):
            if hasattr(record, key):
                data[key] = getattr(record, key)
        return json.dumps(data)


class RequestMiddleware:
    def __init__(self, app, max_body_bytes: int):
        self.app, self.max_body_bytes = app, max_body_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        request_id = uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        started, status = time.perf_counter(), 500

        async def tracked_send(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                message.setdefault("headers", []).append((b"x-request-id", request_id.encode()))
            await send(message)

        async def reject(status_code, code, message):
            await JSONResponse({"code": code, "message": message, "request_id": request_id},
                               status_code=status_code)(scope, receive, tracked_send)

        try:
            if scope["method"] in {"POST", "PUT", "PATCH"}:
                headers = dict(scope["headers"])
                try:
                    length = int(headers.get(b"content-length", b"0"))
                    if length < 0:
                        raise ValueError()
                except ValueError:
                    return await reject(400, "INVALID_PARAMETERS", "Content-Length inválido")
                if length > self.max_body_bytes:
                    return await reject(413, "FILE_TOO_LARGE", "Archivo demasiado grande")
                body = bytearray()
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    body.extend(message.get("body", b""))
                    if len(body) > self.max_body_bytes:
                        return await reject(413, "FILE_TOO_LARGE", "Archivo demasiado grande")
                    if not message.get("more_body", False):
                        break
                delivered = False

                async def replay():
                    nonlocal delivered
                    if not delivered:
                        delivered = True
                        return {"type": "http.request", "body": bytes(body), "more_body": False}
                    return await receive()

                await self.app(scope, replay, tracked_send)
            else:
                await self.app(scope, receive, tracked_send)
        finally:
            logging.getLogger("soundmatch").info("http_request", extra={
                "request_id": request_id, "method": scope["method"], "status": status,
                "duration_ms": round((time.perf_counter() - started) * 1000, 2),
            })
