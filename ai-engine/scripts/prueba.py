import torch
import librosa
import laion_clap

print("PyTorch:", torch.__version__)
print("GPU disponible:", torch.cuda.is_available())
print("Dependencias cargadas correctamente")