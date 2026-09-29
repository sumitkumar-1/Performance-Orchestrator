package com.example.perforchestrator.api;

import com.example.perforchestrator.domain.Problem;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem problem) {
    return ResponseEntity.status(problem.status())
        .body(
            Map.of(
                "code", problem.code(), "field", problem.field(), "message", problem.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> malformed(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "MALFORMED_REQUEST",
                "field",
                "request",
                "message",
                "Request contains missing, unknown or invalid fields"));
  }
}
