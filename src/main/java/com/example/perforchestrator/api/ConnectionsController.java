package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.registry.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ConnectionsController {
  private final ConnectionConfig config;
  private final ArtifactoryImages images;

  public ConnectionsController(ConnectionConfig config, ArtifactoryImages images) {
    this.config = config;
    this.images = images;
  }

  @GetMapping("/connections")
  public Object connections() {
    return config.publicView();
  }

  @GetMapping("/registry-sources/{source}/services/{service}/images")
  public Object discover(
      @PathVariable String source,
      @PathVariable String service,
      @RequestParam(required = false) String username,
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "50") int limit) {
    return images.discover(service, source, username, cursor, limit);
  }

  @GetMapping("/registry-sources/{source}/services/{service}/images/{tag}")
  public Object resolve(
      @PathVariable String source,
      @PathVariable String service,
      @PathVariable String tag,
      @RequestParam(required = false) String username) {
    return images.resolve(service, source, username, tag);
  }
}
