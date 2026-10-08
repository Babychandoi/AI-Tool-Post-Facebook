package com.marketing.shared;

import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(
      org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  void clientDisconnected() {
    // The client closed the image response; writing an error body would fail again.
  }

  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem p) {
    return response(p.status.value(), p.code, p.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> validation(Exception p) {
    return response(
        400, "INVALID_REQUEST", "Dữ liệu không hợp lệ. Vui lòng kiểm tra các trường bắt buộc.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception p) {
    return response(409, "CONFLICT", "Dữ liệu trùng hoặc tham chiếu không hợp lệ.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception p) {
    String trace = UUID.randomUUID().toString();
    LoggerFactory.getLogger(Errors.class)
        .error("Unhandled error trace_id={} type={}", trace, p.getClass().getName());
    return ResponseEntity.status(500)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "code",
                "INTERNAL_ERROR",
                "message",
                "Hệ thống gặp lỗi. Tra cứu bằng mã theo dõi.",
                "trace_id",
                trace,
                "retryable",
                false));
  }

  private ResponseEntity<?> response(int status, String code, String message) {
    return ResponseEntity.status(status)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "trace_id",
                UUID.randomUUID().toString(),
                "retryable",
                false));
  }
}
