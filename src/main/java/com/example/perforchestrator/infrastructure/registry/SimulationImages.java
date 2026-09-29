package com.example.perforchestrator.infrastructure.registry;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import java.util.*;
import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "orchestrator.mode",
    havingValue = "simulation",
    matchIfMissing = true)
@Component
public class SimulationImages implements Ports.ImageResolver, Ports.ImageDiscovery {
  private final Catalog catalog;

  public SimulationImages(Catalog catalog) {
    this.catalog = catalog;
  }

  public List<Image> discover(String service, String source, String username) {
    var svc = catalog.service(service);
    var config = catalog.data().imageSources().get(source);
    if (config == null || !svc.installationBindings().sourceSelectors().containsKey(source))
      throw Problem.invalid(
          "build.sourceRef", "Source is unavailable or lacks an installation mapping");
    if (config.usernameRequired()
        && (username == null || !username.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}")))
      throw Problem.invalid(
          "build.username",
          "A username of 1–40 letters, numbers, hyphens or underscores is required");
    if (!config.usernameRequired() && username != null && !username.isBlank())
      throw Problem.invalid("build.username", "This source does not accept a username");
    String owner = config.usernameRequired() ? username : "";
    String repository =
        config.repositoryTemplate().replace("{service}", service).replace("{username}", owner);
    return config.versions().stream()
        .sorted(Comparator.reverseOrder())
        .map(
            tag ->
                new Image(
                    source, owner, repository, tag, "sha256:" + Json.hash(repository + ":" + tag)))
        .toList();
  }

  public Image resolve(String service, Build build) {
    if (build == null) throw Problem.invalid("build", "Choose an image source and version");
    return discover(service, build.sourceRef(), build.username()).stream()
        .filter(i -> i.version().equals(build.version()))
        .findFirst()
        .orElseThrow(
            () ->
                Problem.invalid(
                    "build.version",
                    "Selected image is unavailable; refresh discovery and choose a version"));
  }
}
