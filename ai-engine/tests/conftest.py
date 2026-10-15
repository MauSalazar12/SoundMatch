import os
from dotenv import load_dotenv
from src.core.config import ENV_FILE

load_dotenv(ENV_FILE)
os.environ.setdefault("DATABASE_URL", "postgresql+psycopg://test:test@localhost:5433/test")
