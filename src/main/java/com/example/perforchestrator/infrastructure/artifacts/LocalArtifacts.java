package com.example.perforchestrator.infrastructure.artifacts;

import com.example.perforchestrator.domain.*;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalArtifacts implements Ports.ArtifactStore {
  private final Path root;

  public LocalArtifacts(@Value("${orchestrator.artifact-root}") String root) throws IOException {
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
  }

  private Path path(String run, String name) {
    if (!run.matches("[a-f0-9-]{36}") || !name.matches("[a-z-]+\\.(json|html)"))
      throw Problem.invalid("artifact", "Invalid artifact identity");
    return root.resolve(run).resolve(name);
  }

  public void write(String run, String name, String content) {
    Path file = path(run, name);
    try {
      Files.createDirectories(file.getParent());
      if (Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file))
        throw new IOException("Symlink artifact");
      Path temp = Files.createTempFile(file.getParent(), "artifact-", ".tmp");
      try {
        Files.writeString(temp, content);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Artifact storage unavailable", e);
    }
  }

  public String read(String run, String name) {
    try {
      return Files.readString(path(run, name));
    } catch (IOException e) {
      throw Problem.missing("Artifact");
    }
  }
}
