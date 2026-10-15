package com.soundmatch.exception;

import com.soundmatch.dto.ApiDtos.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> size(Exception exception, HttpServletRequest request) {
        return response(413, "FILE_TOO_LARGE", "Archivo demasiado grande", request);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class, MultipartException.class})
    ResponseEntity<ApiError> invalid(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_PARAMETERS", "Parámetros o archivo inválidos", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error) {
            return response(error.getStatusCode().value(), "HTTP_ERROR", "Solicitud no válida", request);
        }
        log.atError().addKeyValue("error_type", exception.getClass().getSimpleName()).log("unexpected_error");
        return response(500, "INTERNAL_ERROR", "Error interno", request);
    }

    private ResponseEntity<ApiError> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code, message,
                String.valueOf(request.getAttribute("requestId"))));
    }
}
