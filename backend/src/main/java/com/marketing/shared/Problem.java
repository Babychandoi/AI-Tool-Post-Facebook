package com.marketing.shared;

import org.springframework.http.HttpStatus;

public class Problem extends RuntimeException {
  public final HttpStatus status;
  public final String code;

  public Problem(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static Problem bad(String message) {
    return new Problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  public static Problem conflict(String message) {
    return new Problem(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  public static Problem missing() {
    return new Problem(
        HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy dữ liệu trong phạm vi được cấp.");
  }
}
