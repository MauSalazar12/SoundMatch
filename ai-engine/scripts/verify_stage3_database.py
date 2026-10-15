"""Read-only row fingerprints for before/after stage-3 verification."""
import argparse
import json
from pathlib import Path

from sqlalchemy import text
from src.core.database import engine


def snapshot() -> dict:
    with engine.connect() as connection:
        connection.execute(text("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"))
        return {table: dict(connection.execute(text(
            f"SELECT count(*) count, md5(string_agg(row_to_json(t)::text, '' ORDER BY id)) checksum FROM {table} t"
        )).mappings().one()) for table in ("songs", "audio_embeddings")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    current = snapshot()
    if args.check:
        if current != json.loads(args.path.read_text(encoding="utf-8")):
            raise SystemExit("Database contents differ from the snapshot")
        print("Database unchanged:", json.dumps(current))
    else:
        # Exclusive creation prevents accidentally replacing the baseline.
        with args.path.open("x", encoding="utf-8") as destination:
            json.dump(current, destination, indent=2)
        print("Snapshot created:", json.dumps(current))


if __name__ == "__main__":
    main()
