from src.core.config import AUDIO_DIR
import numpy as np

from src.audio.audio_processing import load_audio, split_audio

def main():
    from src.embeddings.clap_encoder import CLAPEncoder
    audio = load_audio(
        AUDIO_DIR / "000" / "000002.mp3"
    )

    segments = split_audio(audio)

    if not segments:
        raise ValueError("No hay segmentos válidos para la prueba")

    encoder = CLAPEncoder()

    embedding = encoder.encode(
        segments[0]["audio"]
    )

    print("Shape:", embedding.shape)
    print("Norma:", np.linalg.norm(embedding))
    print("Primeros valores:", embedding[:5])

    if embedding.shape != (512,):
        raise ValueError(
            f"Dimensión incompatible con VECTOR(512): {embedding.shape}"
        )

if __name__ == "__main__":
    main()
