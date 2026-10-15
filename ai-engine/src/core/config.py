"""Shared paths, resolved relative to ai-engine rather than the working directory."""
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = PROJECT_ROOT / "data"
AUDIO_DIR = DATA_DIR / "fma_small"
METADATA = DATA_DIR / "fma_metadata" / "tracks.csv"
OUTPUT = PROJECT_ROOT / "output" / "catalog.csv"
ENV_FILE = PROJECT_ROOT / ".env"
CHECKPOINT = PROJECT_ROOT / "models" / "music_audioset_epoch_15_esc_90.14.pt"
MODEL_VERSION = "clap-music-audioset-v1"


def resolve_project_path(path):
    """Resolve existing relative catalog/database paths without rewriting records."""
    path = Path(path)
    return path if path.is_absolute() else PROJECT_ROOT / path
