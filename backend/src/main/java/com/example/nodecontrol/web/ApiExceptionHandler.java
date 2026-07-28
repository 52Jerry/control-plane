package com.example.nodecontrol.web;

import com.example.nodecontrol.client.RemoteNodeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RemoteNodeException.class)
    public ResponseEntity<ErrorResponse> remoteNode(RemoteNodeException exception) {
        int remoteStatus = exception.getStatusCode();
        HttpStatus status = remoteStatus >= 400 && remoteStatus < 500
                ? HttpStatus.resolve(remoteStatus)
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status == null ? HttpStatus.BAD_GATEWAY : status)
                .body(new ErrorResponse(exception.getMessage(), remoteStatus, Instant.now(), null));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(exception.getMessage(), 404, Instant.now(), null));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> badRequest(RuntimeException exception) {
        HttpStatus status = exception instanceof DataIntegrityViolationException || exception instanceof IllegalStateException
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(exception.getMessage(), status.value(), Instant.now(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("请求参数不正确", 400, Instant.now(), fields));
    }

    public record ErrorResponse(
            String message,
            int status,
            Instant timestamp,
            Map<String, String> fields
    ) {
    }
}

