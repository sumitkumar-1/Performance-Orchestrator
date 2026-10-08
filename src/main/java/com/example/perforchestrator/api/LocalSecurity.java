package com.example.perforchestrator.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class LocalSecurity {

  @Bean
  SecurityFilterChain security(final HttpSecurity http) throws Exception {
    return http
      .authorizeHttpRequests((final var auth) -> auth.anyRequest().permitAll())
      .csrf((final var csrf) -> {})
      .headers((final var headers) ->
        headers.addHeaderWriter((final var request, final var response) ->
          response.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'nonce-" +
              EditorStyles.nonce(request) +
              "'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
          )
        )
      )
      .addFilterBefore(new LoopbackFilter(), CsrfFilter.class)
      .build();
  }

  static class LoopbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain chain
    ) throws ServletException, IOException {
      final String host = request.getHeader("Host");
      boolean valid = false;
      try {
        final URI uri = URI.create("http://" + host);
        valid =
          Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()) &&
          uri.getRawUserInfo() == null &&
          (uri.getRawPath() == null || uri.getRawPath().isEmpty());
      } catch (final RuntimeException ignored) {}
      final String origin = request.getHeader("Origin");
      if (
        !valid ||
        (origin != null && !origin.equals(request.getScheme() + "://" + host)) ||
        "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
      ) {
        response.sendError(403, "Only same-origin loopback requests are accepted");
        return;
      }
      if (
        request.getContentLengthLong() > 262144 || request.getHeader("Transfer-Encoding") != null
      ) {
        response.sendError(413, "Request too large or unbounded");
        return;
      }
      EditorStyles.nonce(request);
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
      chain.doFilter(request, response);
    }
  }
}
