package com.example.perforchestrator.infrastructure.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.core.io.ClassPathResource;

public final class ConfigurationResources {
  private ConfigurationResources() {}

  public static String read(String location) throws IOException {
    try (var stream =
        location.startsWith("classpath:")
            ? new ClassPathResource(location.substring(10)).getInputStream()
            : Files.newInputStream(Path.of(location))) {
      byte[] bytes = stream.readNBytes(262145);
      if (bytes.length > 262144) throw new IOException("Configuration resource exceeds size limit");
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }
}
