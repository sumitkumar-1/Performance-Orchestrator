package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.RequestAuthentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ConnectionsController {

  private final ConnectionConfig config;
  private final ArtifactoryImages images;

  public ConnectionsController(final ConnectionConfig config, final ArtifactoryImages images) {
    this.config = config;
    this.images = images;
  }

  @GetMapping("/connections")
  public Object connections() {
    final var view = new java.util.LinkedHashMap<>(config.publicView());
    view.put("imageSources", images.sources());
    return view;
  }

  public record ImageQuery(
    String username,
    String cursor,
    Integer limit,
    String tag,
    RequestAuthentication authentication
  ) {
    @Override
    public String toString() {
      return "[REDACTED image query]";
    }
  }

  @PostMapping("/registry-sources/{source}/services/{service}/images/query")
  public Object query(
    final @PathVariable String source,
    final @PathVariable String service,
    final @RequestBody ImageQuery query
  ) {
    return query.tag() == null
      ? images.discover(
          service,
          source,
          query.username(),
          query.cursor(),
          query.limit() == null ? 50 : query.limit(),
          query.authentication()
        )
      : images.resolve(service, source, query.username(), query.tag(), query.authentication());
  }

  @GetMapping("/registry-sources/{source}/services/{service}/images")
  public Object discover(
    final @PathVariable String source,
    final @PathVariable String service,
    final @RequestParam(required = false) String username,
    final @RequestParam(defaultValue = "") String cursor,
    final @RequestParam(defaultValue = "50") int limit
  ) {
    return images.discover(service, source, username, cursor, limit);
  }

  @GetMapping("/registry-sources/{source}/services/{service}/images/{tag}")
  public Object resolve(
    final @PathVariable String source,
    final @PathVariable String service,
    final @PathVariable String tag,
    final @RequestParam(required = false) String username
  ) {
    return images.resolve(service, source, username, tag);
  }
}
