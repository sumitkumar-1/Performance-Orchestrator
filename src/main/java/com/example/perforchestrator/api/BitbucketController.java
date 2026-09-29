package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.registry.BitbucketReferences;
import com.example.perforchestrator.infrastructure.secrets.RequestAuthentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/service-projects")
public class BitbucketController {
  private final BitbucketReferences references;
  public BitbucketController(BitbucketReferences references) { this.references = references; }
  public record Query(String kind, int start, RequestAuthentication authentication) {
    @Override public String toString() { return "[REDACTED reference query]"; }
  }
  @PostMapping("/{service}/references/query")
  public Object list(@PathVariable String service, @RequestBody Query query) {
    return references.list(service, query.kind(), query.start(), query.authentication());
  }
}
