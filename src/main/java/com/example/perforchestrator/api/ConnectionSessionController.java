package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.secrets.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/connection-auth/{kind}/{connection}")
public class ConnectionSessionController {
  private final ConnectionSessions sessions;
  public ConnectionSessionController(ConnectionSessions sessions) { this.sessions = sessions; }
  public record Login(RequestAuthentication authentication, int lifetimeSeconds) {
    @Override public String toString() { return "[REDACTED]"; }
  }
  @GetMapping public Object status(@PathVariable String kind, @PathVariable String connection, HttpServletRequest request) {
    return sessions.status(kind, connection, request.getSession(false));
  }
  @PostMapping public Object login(@PathVariable String kind, @PathVariable String connection, @RequestBody Login input, HttpServletRequest request) {
    var status = sessions.remember(kind, connection, input.authentication(), input.lifetimeSeconds(), request.getSession());
    request.changeSessionId();
    return status;
  }
  @DeleteMapping public Object logout(@PathVariable String kind, @PathVariable String connection, HttpServletRequest request) {
    sessions.forget(kind, connection, request.getSession(false));
    return Map.of("cleared", true);
  }
}
