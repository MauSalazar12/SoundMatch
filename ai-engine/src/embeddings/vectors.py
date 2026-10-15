"""Shared vector validation used by ingestion and HTTP inference."""
import numpy as np


def normalize(vector):
    norm = np.linalg.norm(vector)
    if norm == 0 or not np.isfinite(norm):
        raise ValueError("Vector inválido")
    return vector / norm


def to_vector_literal(vector):
    vector = np.asarray(vector, dtype=np.float32)
    if vector.shape != (512,):
        raise ValueError(f"Dimensión incompatible: {vector.shape}")
    if not np.isfinite(vector).all():
        raise ValueError("Vector con valores inválidos")
    return "[" + ",".join(map(str, vector.tolist())) + "]"
