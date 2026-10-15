
import os

from dotenv import load_dotenv
from sqlalchemy import create_engine, text

from src.core.config import ENV_FILE

load_dotenv(ENV_FILE)

DATABASE_URL = os.environ["DATABASE_URL"]

engine = create_engine(
    DATABASE_URL,
    pool_pre_ping=True,
    connect_args={"connect_timeout": 5}
)

import time

def test_connection():
    start = time.perf_counter()

    with engine.connect() as connection:
        elapsed = time.perf_counter() - start

        print(f"Tiempo de conexión: {elapsed:.3f} segundos")

        result = connection.execute(
            text("SELECT current_database()")
        )

        print("Base de datos:", result.scalar())


if __name__ == "__main__":
    test_connection()
