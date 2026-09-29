package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Argument arrays only; no shell, command logging, or unbounded process output. */
@Component
public class CommandRunner {
  public record Result(int exit, String output) {}
  public Result run(List<String> args, Path directory, Map<String,String> environment, Duration timeout) {
    Process process = null;
    var reader = Executors.newSingleThreadExecutor();
    try {
      var builder = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
      builder.environment().putAll(environment);
      process = builder.start();
      process.getOutputStream().close();
      final Process running = process;
      var output = reader.submit(() -> {
        try (var stream = running.getInputStream(); var bytes = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192]; int n;
          while ((n = stream.read(buffer)) >= 0) {
            if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("Output limit exceeded");
            bytes.write(buffer, 0, n);
          }
          return bytes.toString(StandardCharsets.UTF_8);
        }
      });
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new TimeoutException();
      return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "COMMAND_INTERRUPTED", "execution", "Execution interrupted; verify owned resources before retrying");
    } catch (Exception e) {
      throw new Problem(503, "COMMAND_UNAVAILABLE", "execution", "Git/Helm/kubectl command failed or timed out; check installed tools, network, trust and permissions");
    } finally {
      if (process != null && process.isAlive()) {
        try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
        catch (RuntimeException ignored) { /* Some sandboxed hosts deny process enumeration. */ }
        finally { process.destroyForcibly(); }
      }
      reader.shutdownNow();
    }
  }
  public String require(List<String> args, Path directory, Map<String,String> environment, Duration timeout, String operation) {
    var result = run(args, directory, environment, timeout);
    if (result.exit() != 0) throw new Problem(502, "EXECUTION_COMMAND_FAILED", "execution", operation + " failed (exit " + result.exit() + "); raw command output is withheld because charts may contain secrets");
    return result.output();
  }
}
