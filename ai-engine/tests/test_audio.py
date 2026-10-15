from src.core.config import AUDIO_DIR
from src.audio.audio_processing import load_audio, split_audio

def main():
    path = AUDIO_DIR / "000" / "000002.mp3"

    audio = load_audio(path)
    segments = split_audio(audio)

    print("Muestras totales:", len(audio))
    print("Fragmentos:", len(segments))

    for index, segment in enumerate(segments):
        print(index, segment["audio"].shape)

if __name__ == "__main__":
    main()
