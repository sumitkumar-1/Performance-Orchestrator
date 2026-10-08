package com.example.perforchestrator.api;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;

/** Allows only this browser session's generated editor styles; scripts remain self-only. */
final class EditorStyles {

  private static final String ATTRIBUTE = EditorStyles.class.getName();
  private static final SecureRandom RANDOM = new SecureRandom();

  private EditorStyles() {}

  static String nonce(final HttpServletRequest request) {
    final var session = request.getSession();
    synchronized (session) {
      final var existing = session.getAttribute(ATTRIBUTE);
      if (existing instanceof final String value) return value;
      final byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      final String value = Base64.getEncoder().encodeToString(bytes);
      session.setAttribute(ATTRIBUTE, value);
      return value;
    }
  }
}
