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
    return runWithInput(args,directory,environment,timeout,new byte[0]);
  }
  public Result runWithInput(List<String> args, Path directory, Map<String,String> environment, Duration timeout, byte[] input) {
    var diagnostic=com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog.begin("COMMAND",
        com.example.perforchestrator.infrastructure.diagnostics.SafeDiagnostics.command(args));
    Process process = null;
    var reader = Executors.newSingleThreadExecutor();
    try {
      var builder = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
      builder.environment().putAll(environment);
      process = builder.start();
      try(var stdin=process.getOutputStream()){stdin.write(input);}
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
      var result=new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
      String category="";
      if(result.exit()!=0){String lower=result.output().toLowerCase(Locale.ROOT);
        if(lower.contains("getting credentials") || lower.contains("exec plugin") || lower.contains("exec: executable"))category=" · CREDENTIAL HELPER FAILURE";
        else if(lower.contains("unknown flag"))category=" · UNSUPPORTED OPTION";
        else if(lower.contains("forbidden"))category=" · ACCESS FORBIDDEN";
        else if(lower.contains("unauthorized"))category=" · AUTHENTICATION REJECTED";
      }
      diagnostic.finish("EXIT "+result.exit()+category);return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      diagnostic.finish("INTERRUPTED");
      throw new Problem(503, "COMMAND_INTERRUPTED", "execution", "Execution interrupted; verify owned resources before retrying");
    } catch (Exception e) {
      diagnostic.finish(e instanceof TimeoutException?"TIMED OUT":"COMMAND UNAVAILABLE");
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
    if(result.exit()!=0 && (operation.equals("Helm release lookup") || operation.equals("Helm release status")))
      throw ClusterDiagnostics.failure(operation,result.exit(),result.output());
    if (result.exit() != 0) throw new Problem(502, "EXECUTION_COMMAND_FAILED", "execution", operation + " failed (exit " + result.exit() + "); raw command output is withheld because charts may contain secrets");
    return result.output();
  }
}
