package com.example.perforchestrator.domain;

public class Problem extends RuntimeException {

  private final int status;
  private final String code;
  private final String field;

  public Problem(final int status, final String code, final String field, final String message) {
    super(message);
    this.status = status;
    this.code = code;
    this.field = field;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String field() {
    return field;
  }

  public static Problem invalid(final String field, final String message) {
    return new Problem(422, "VALIDATION", field, message);
  }

  public static Problem conflict(final String message) {
    return new Problem(409, "CONFLICT", "", message);
  }

  public static Problem missing(final String kind) {
    return new Problem(404, "NOT_FOUND", "", kind + " not found");
  }
}
