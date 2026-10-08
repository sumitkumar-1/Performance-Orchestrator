package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.infrastructure.secrets.AdSessionCredentials;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Responds once to each explicit cluster-login prompt, never sends unsolicited input. */
final class LoginPrompts {

  private static final Pattern PROMPT = Pattern.compile(
    "(?i)^(?:enter\\s+(?:your\\s+)?|ad\\s+)?(username|user name|password)(?:\\s*\\([^\\r\\n]*\\))?\\s*[:>]\\s*$"
  );
  private final AdSessionCredentials.Plaintext credentials;
  private final OutputStream input;
  private final StringBuilder line = new StringBuilder();
  private boolean usernameSent;
  private boolean passwordSent;

  LoginPrompts(final AdSessionCredentials.Plaintext credentials, final OutputStream input) {
    this.credentials = credentials;
    this.input = input;
  }

  void accept(final byte[] bytes, final int length) throws IOException {
    if (credentials == null) return;
    for (final char character : new String(
      bytes,
      0,
      length,
      StandardCharsets.UTF_8
    ).toCharArray()) {
      if (character == '\n' || character == '\r') {
        respond();
        line.setLength(0);
      } else if (line.length() < 1024) line.append(character);
    }
    respond();
  }

  private void respond() throws IOException {
    final var match = PROMPT.matcher(
      line.toString().replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "").strip()
    );
    if (!match.matches()) return;
    final boolean password = match.group(1).equalsIgnoreCase("password");
    if (password ? passwordSent : usernameSent) return;
    final byte[] response = credentials.response(password);
    try {
      input.write(response);
      input.flush();
    } finally {
      Arrays.fill(response, (byte) 0);
    }
    if (password) passwordSent = true;
    else usernameSent = true;
    line.setLength(0);
  }
}
