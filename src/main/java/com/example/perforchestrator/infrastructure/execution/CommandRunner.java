package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.secrets.AdSessionCredentials;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Argument arrays, bounded output and secret-safe command diagnostics. */
@Component
public class CommandRunner {

  public record Result(int exit, String output) {}

  public Result run(
    final List<String> args,
    final Path directory,
    final Map<String, String> environment,
    final Duration timeout
  ) {
    return runWithInput(args, directory, environment, timeout, new byte[0]);
  }

  public Result runWithInput(
    final List<String> args,
    final Path directory,
    final Map<String, String> environment,
    final Duration timeout,
    final byte[] input
  ) {
    final var diagnostic =
      com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog.begin(
        "COMMAND",
        com.example.perforchestrator.infrastructure.diagnostics.SafeDiagnostics.command(args)
      );
    final String executable = args.isEmpty()
      ? ""
      : Path.of(args.getFirst()).getFileName().toString();
    final boolean clusterCommand =
      !args.isEmpty() &&
      ((executable.equals("helm") && args.contains("--kube-context")) ||
        (executable.equals("kubectl") && args.contains("--context")));
    final var credentials =
      clusterCommand && input.length == 0 ? AdSessionCredentials.open() : null;
    // A successful template command returns machine-readable YAML on stdout only.
    final boolean template =
      executable.equals("helm") && args.size() > 1 && args.get(1).equals("template");
    Process process = null;
    final var reader = Executors.newFixedThreadPool(template ? 2 : 1);
    try {
      final var builder = new ProcessBuilder(args)
        .directory(directory.toFile())
        .redirectErrorStream(!template);
      builder.environment().putAll(environment);
      process = builder.start();
      if (credentials == null) try (var stdin = process.getOutputStream()) {
        stdin.write(input);
      }
      final Process running = process;
      final Future<byte[]> errors = template
        ? reader.submit(() -> {
            try (final var stream = running.getErrorStream()) {
              final byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
              if (bytes.length > 4 * 1024 * 1024) throw new IOException("Output limit exceeded");
              return bytes;
            }
          })
        : null;
      final var output = reader.submit(() -> {
        try (var stream = running.getInputStream(); var bytes = new ByteArrayOutputStream()) {
          final var prompts = new LoginPrompts(credentials, running.getOutputStream());
          final byte[] buffer = new byte[8192];
          int n;
          while ((n = stream.read(buffer)) >= 0) {
            if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("Output limit exceeded");
            bytes.write(buffer, 0, n);
            prompts.accept(buffer, n);
          }
          final String text = bytes.toString(StandardCharsets.UTF_8);
          return credentials == null ? text : credentials.redact(text);
        }
      });
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new TimeoutException();
      final String stdout = output.get(5, TimeUnit.SECONDS);
      final String stderr =
        errors == null ? "" : new String(errors.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
      final var result = new Result(
        process.exitValue(),
        stdout + (process.exitValue() == 0 ? "" : "\n" + stderr)
      );
      String category = "";
      if (result.exit() != 0) {
        final String lower = result.output().toLowerCase(Locale.ROOT);
        if (
          lower.contains("getting credentials") ||
          lower.contains("exec plugin") ||
          lower.contains("exec: executable")
        ) category = " · CREDENTIAL HELPER FAILURE";
        else if (lower.contains("unknown flag")) category = " · UNSUPPORTED OPTION";
        else if (lower.contains("forbidden")) category = " · ACCESS FORBIDDEN";
        else if (lower.contains("unauthorized")) category = " · AUTHENTICATION REJECTED";
      }
      diagnostic.finish(
        "EXIT " +
          result.exit() +
          category +
          (template && result.exit() == 0 && !stderr.isBlank()
            ? " · STDERR PRESENT (excluded from rendered YAML)"
            : "")
      );
      return result;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      diagnostic.finish("INTERRUPTED");
      throw new Problem(
        503,
        "COMMAND_INTERRUPTED",
        "execution",
        "Execution interrupted; verify owned resources before retrying"
      );
    } catch (final Exception e) {
      diagnostic.finish(e instanceof TimeoutException ? "TIMED OUT" : "COMMAND UNAVAILABLE");
      throw new Problem(
        503,
        "COMMAND_UNAVAILABLE",
        "execution",
        "Git/Helm/kubectl command failed or timed out; check installed tools, network, trust and permissions"
      );
    } finally {
      if (process != null && process.isAlive()) {
        try {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (final RuntimeException ignored) {
          /* Some sandboxed hosts deny process enumeration. */
        } finally {
          process.destroyForcibly();
        }
      }
      reader.shutdownNow();
      if (credentials != null) credentials.close();
    }
  }

  public String require(
    final List<String> args,
    final Path directory,
    final Map<String, String> environment,
    final Duration timeout,
    final String operation
  ) {
    final var result = run(args, directory, environment, timeout);
    if (
      result.exit() != 0 &&
      (operation.equals("Helm release lookup") || operation.equals("Helm release status"))
    ) throw ClusterDiagnostics.failure(operation, result.exit(), result.output());
    if (result.exit() != 0) throw new Problem(
      502,
      "EXECUTION_COMMAND_FAILED",
      "execution",
      operation +
        " failed (exit " +
        result.exit() +
        "); raw command output is withheld because charts may contain secrets"
    );
    return result.output();
  }
}
