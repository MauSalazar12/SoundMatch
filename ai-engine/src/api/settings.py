import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv
from src.core.config import CHECKPOINT, ENV_FILE, resolve_project_path


@dataclass(frozen=True)
class Settings:
    checkpoint: Path = CHECKPOINT
    device: str = "auto"
    max_upload_bytes: int = 20 * 1024 * 1024
    max_audio_seconds: float = 60
    inference_timeout_seconds: float = 90
    candidate_limit: int = 1000

    def __post_init__(self):
        if self.max_upload_bytes < 1 or self.max_audio_seconds < 10:
            raise ValueError("Invalid audio limits")
        if self.inference_timeout_seconds <= 0 or not 100 <= self.candidate_limit <= 1000:
            raise ValueError("Invalid inference settings")
        if self.device not in {"auto", "cpu", "cuda", "cuda:0"}:
            raise ValueError("CLAP_DEVICE must be auto, cpu, cuda or cuda:0")

    @classmethod
    def from_env(cls):
        load_dotenv(ENV_FILE)
        return cls(
            checkpoint=resolve_project_path(os.getenv("CLAP_CHECKPOINT", str(CHECKPOINT))),
            device=os.getenv("CLAP_DEVICE", "auto"),
            max_upload_bytes=int(os.getenv("MAX_UPLOAD_BYTES", 20 * 1024 * 1024)),
            max_audio_seconds=float(os.getenv("MAX_AUDIO_SECONDS", 60)),
            inference_timeout_seconds=float(os.getenv("INFERENCE_TIMEOUT_SECONDS", 90)),
            candidate_limit=int(os.getenv("CANDIDATE_LIMIT", 1000)),
        )
