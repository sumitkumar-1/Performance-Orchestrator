package com.example.perforchestrator.infrastructure.diagnostics;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Preserve operational details; credential-bearing options, URLs and assignments are masked. */
public final class SafeDiagnostics {

  private SafeDiagnostics() {}

  private static final Pattern SENSITIVE = Pattern.compile(
    "(?i).*(password|passwd|token|secret|authorization|cookie|credential|api[-_.]?key|access[-_.]?key|private[-_.]?key).*"
  );
  private static final Set<String> SECRET_FLAGS = Set.of(
    "--password",
    "--token",
    "--kube-token",
    "--client-secret",
    "--authorization",
    "--access-token",
    "--api-key",
    "--bearer-token",
    "-p"
  );
  private static final Set<String> HEADER_FLAGS = Set.of("-H", "--header", "--headers");

  public static String command(final List<String> args) {
    if (args.isEmpty()) return "Command";
    String executable = args.getFirst().replace('\\', '/');
    executable = executable.substring(executable.lastIndexOf('/') + 1);
    if (!Set.of("helm", "git", "kubectl").contains(executable)) return (
      clean(executable) + " [arguments omitted]"
    );
    final List<String> result = new ArrayList<>();
    boolean secretNext = false,
      headerNext = false;
    for (final String arg : args) {
      if (secretNext) {
        result.add("[redacted]");
        secretNext = false;
        continue;
      }
      if (headerNext) {
        result.add(quote(maskAssignments(arg)));
        headerNext = false;
        continue;
      }
      final int equals = arg.indexOf('=');
      final String flag = equals < 0 ? arg : arg.substring(0, equals);
      boolean sensitiveFlag =
        SECRET_FLAGS.contains(flag) || (flag.startsWith("--") && SENSITIVE.matcher(flag).matches());
      // These flags name transport/file mechanisms, not a credential value.
      if (
        Set.of("--password-stdin", "--password-file", "--token-file").contains(flag)
      ) sensitiveFlag = false;
      if (sensitiveFlag) {
        result.add(flag + (equals < 0 ? "" : "=[redacted]"));
        secretNext = equals < 0;
      } else if (HEADER_FLAGS.contains(flag)) {
        if (equals < 0) {
          result.add(flag);
          headerNext = true;
        } else result.add(flag + "=" + quote(maskAssignments(arg.substring(equals + 1))));
      } else result.add(quote(maskAssignments(maskUrl(arg))));
    }
    return String.join(" ", result);
  }

  public static String endpoint(final String method, final URI uri) {
    return method + " " + clean(url(uri));
  }

  private static String maskUrl(final String text) {
    if (
      text.startsWith("https://") || text.startsWith("http://") || text.startsWith("oci://")
    ) try {
      return url(URI.create(text));
    } catch (final IllegalArgumentException ignored) {
      return "[invalid URL]";
    }
    return text;
  }

  private static String url(final URI uri) {
    String query = uri.getRawQuery();
    if (query != null) {
      final List<String> parts = new ArrayList<>();
      for (final String pair : query.split("&", -1)) {
        final int at = pair.indexOf('=');
        final String key = at < 0 ? pair : pair.substring(0, at),
          value = at < 0 ? "" : pair.substring(at + 1);
        final String decoded = URLDecoder.decode(key, StandardCharsets.UTF_8);
        if (
          SENSITIVE.matcher(decoded).matches() ||
          decoded.toLowerCase(Locale.ROOT).contains("signature")
        ) parts.add(key + "=[redacted]");
        else parts.add(
          key +
            (at < 0
              ? ""
              : "=" +
                URLEncoder.encode(
                  maskAssignments(URLDecoder.decode(value, StandardCharsets.UTF_8)),
                  StandardCharsets.UTF_8
                ))
        );
      }
      query = String.join("&", parts);
    }
    String host = uri.getHost();
    if (host != null && host.contains(":")) host = "[" + host + "]";
    return (
      uri.getScheme() +
      "://" +
      (uri.getRawUserInfo() == null ? "" : "[redacted]@") +
      host +
      (uri.getPort() < 0 ? "" : ":" + uri.getPort()) +
      Objects.toString(uri.getRawPath(), "") +
      (query == null ? "" : "?" + query) +
      (uri.getRawFragment() == null ? "" : "#[redacted]")
    );
  }

  private static String maskAssignments(final String text) {
    final String result = text
      .replaceAll("(?i)((?:authorization|cookie|set-cookie)\\s*[:=]).*", "$1[redacted]")
      .replaceAll("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+", "$1 [redacted]");
    // Applies to headers, git -c settings, Helm --set assignments and credential labels in queries.
    return result.replaceAll(
      "(?i)([A-Za-z0-9_.-]*(?:password|passwd|token|secret|authorization|cookie|credential|api[-_.]?key|access[-_.]?key|private[-_.]?key)[A-Za-z0-9_.-]*\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)",
      "$1[redacted]"
    );
  }

  private static String clean(final String text) {
    return text
      .replace("\r", "\\r")
      .replace("\n", "\\n")
      .replace("\t", "\\t")
      .replace("\u001b", "[ESC]");
  }

  private static String quote(String text) {
    text = clean(text);
    return text.matches("[A-Za-z0-9_./:=@+,-]+") ? text : "'" + text.replace("'", "'\\''") + "'";
  }
}
