package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.config.ConfigurationAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps a request on one configuration revision, including when hosted under a context path. */
@Component
public final class ConfigurationRequestFilter extends OncePerRequestFilter {
  private final ConfigurationAccess access;

  public ConfigurationRequestFilter(ConfigurationAccess access) {
    this.access = access;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean update = path.equals("/api/v1/configuration") && request.getMethod().equals("PUT");
    try (var scope = update ? access.write() : access.read()) {
      chain.doFilter(request, response);
    }
  }
}
