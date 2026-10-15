from sqlalchemy import text
from src.core.database import engine
from src.core.config import MODEL_VERSION

COLUMNS = "id, title, artist, genre, duration_seconds, status"


class CatalogRepository:
    def health(self) -> bool:
        with engine.connect() as connection:
            return connection.execute(text("SELECT 1")).scalar_one() == 1

    def get(self, song_id: int) -> dict | None:
        with engine.connect() as connection:
            row = connection.execute(text(f"SELECT {COLUMNS} FROM songs WHERE id=:id"),
                                     {"id": song_id}).mappings().first()
        return dict(row) if row else None

    def has_embedding(self, song_id: int, segment_index: int = 0) -> bool:
        with engine.connect() as connection:
            return connection.execute(text("""
                SELECT EXISTS (SELECT 1 FROM audio_embeddings
                WHERE song_id=:id AND segment_index=:segment AND model_version=:version)
            """), {"id": song_id, "segment": segment_index, "version": MODEL_VERSION}).scalar_one()

    def list(self, page: int, size: int, title=None, artist=None, genre=None) -> dict:
        conditions, params = [], {"limit": size, "offset": page * size}
        for column, value in (("title", title), ("artist", artist), ("genre", genre)):
            if value:
                # Literal substring matching, including '%' and '_' supplied by callers.
                conditions.append(f"strpos(lower({column}), lower(:{column})) > 0")
                params[column] = value
        where = " WHERE " + " AND ".join(conditions) if conditions else ""
        with engine.connect() as connection:
            total = connection.execute(text("SELECT count(*) FROM songs" + where), params).scalar_one()
            rows = connection.execute(text(f"SELECT {COLUMNS} FROM songs" + where +
                                           " ORDER BY id LIMIT :limit OFFSET :offset"), params).mappings().all()
        return {"items": [dict(row) for row in rows], "total": total, "page": page, "size": size}
