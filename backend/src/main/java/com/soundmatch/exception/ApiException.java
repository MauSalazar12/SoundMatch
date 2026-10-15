package com.soundmatch.exception;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException upstream(int status) {
        return switch (status) {
            case 400, 422 -> new ApiException(400, "INVALID_PARAMETERS", "Parámetros o audio inválidos");
            case 404 -> new ApiException(404, "SONG_NOT_FOUND", "Canción inexistente");
            case 413 -> new ApiException(413, "FILE_TOO_LARGE", "Archivo demasiado grande");
            case 415 -> new ApiException(415, "UNSUPPORTED_AUDIO", "Audio inválido o no compatible");
            case 504 -> new ApiException(504, "INFERENCE_TIMEOUT", "Tiempo de inferencia agotado");
            case 500 -> new ApiException(500, "INTERNAL_ERROR", "Error interno del motor de IA");
            default -> new ApiException(503, "AI_UNAVAILABLE", "Servicio de IA no disponible");
        };
    }
}
