import laion_clap
import numpy as np
import torch
from pathlib import Path

from src.core.config import CHECKPOINT, MODEL_VERSION
from src.embeddings.vectors import normalize, to_vector_literal


class CLAPEncoder:
    def __init__(self, checkpoint=CHECKPOINT, device=None):
        checkpoint = Path(checkpoint)
        if not checkpoint.is_file():
            raise FileNotFoundError(checkpoint)

        self.model = laion_clap.CLAP_Module(
            enable_fusion=False,
            amodel="HTSAT-base",
            device=device,
        )

        self.model.load_ckpt(str(checkpoint), verbose=False)
        self.model.model.eval()

    @torch.inference_mode()
    def encode(self, audio):
        batch = np.asarray(
            audio,
            dtype=np.float32,
        ).reshape(1, -1)

        result = self.model.get_audio_embedding_from_data(
            x=batch,
            use_tensor=False,
        )

        embedding = np.asarray(
            result[0],
            dtype=np.float32,
        )

        to_vector_literal(embedding)  # Enforce the existing VECTOR(512) contract.
        return normalize(embedding)
