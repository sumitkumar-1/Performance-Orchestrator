package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class ConnectionAuthenticationTest {

  final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
  final CredentialResolver resolver = mock(CredentialResolver.class);

  ReadOnlyHttp.Response response(final String json) {
    return new ReadOnlyHttp.Response(200, Map.of(), json.getBytes(StandardCharsets.UTF_8));
  }

  ConnectionConfig config(final String mode) {
    return new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(
          "registry",
          new ConnectionConfig.Artifactory(
            "https://registry.invalid/artifactory/api/docker",
            null,
            mode
          )
        ),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(
          "stash",
          new ConnectionConfig.Bitbucket("https://stash.invalid/rest/api", null, mode)
        )
      )
    );
  }

  Catalog catalog() throws Exception {
    final var catalog = new Catalog("", "127.0.0.1");
    final var service = new Catalog.Service(
      "projects/receiver",
      Map.of(),
      new Catalog.Destination("receiver", "receiver", List.of("ckp/helm/values.yaml")),
      new Catalog.ContainerImage(
        "registry",
        "stable",
        "ps-spoolers",
        "ps-spoolers-sng-smtp-receiver"
      ),
      new Catalog.SourceProject("stash", "SP", "receiver", "v1", "ckp/helm/receiver")
    );
    catalog.replace(new Catalog.Data(Map.of(), Map.of("receiver", service), Map.of()));
    return catalog;
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
  }

  /**
   * <b>Scenario:</b> Registry Uses Service Stage And Image Path With Bearer Token
   * <pre>
   * GIVEN ... a registry connection and a service image path
   * WHEN ... tags are requested for the selected repository stage
   * THEN ... the Docker tags URL and bearer authorization match the service configuration
   * </pre>
   */
  @Test
  @DisplayName("Registry Uses Service Stage And Image Path With Bearer Token")
  void registryUsesServiceStageAndImagePathWithBearerToken() throws Exception {
    final var config = config("token");
    final var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(http.get(any(), any(), any())).thenReturn(response("{\"tags\":[\"v1\"]}"));
    final var images = new ArtifactoryImages(config, http, resolver, catalog());
    final var input = new RequestAuthentication(null, null, "private-token");
    assertThat(
      images.discover("receiver", "service:receiver", null, "", 50, input).versions()
    ).containsExactly("v1");
    verify(http).get(
      URI.create(
        "https://registry.invalid/artifactory/api/docker/docker-stable/v2/ps-spoolers/ps-spoolers-sng-smtp-receiver/tags/list?n=50"
      ),
      "Bearer private-token",
      "application/json"
    );
    assertThat(request.getSession(false)).isNull();
    assertThat(Json.write(config.data())).doesNotContain("ad-user", "private-password");
    assertThat(input.toString()).isEqualTo("[REDACTED]");
    assertThatThrownBy(() ->
      images.discover("receiver", "service:receiver", null, "", 50)
    ).hasMessageContaining("Sign in");
    verifyNoInteractions(resolver);
  }

  /**
   * <b>Scenario:</b> Bitbucket Uses Bearer Token And Bounds Pagination
   * <pre>
   * GIVEN ... a token-authenticated Bitbucket project
   * WHEN ... branches and tags are listed across pages
   * THEN ... requests use bearer authorization and invalid pagination responses are rejected
   * </pre>
   */
  @Test
  @DisplayName("Bitbucket Uses Bearer Token And Bounds Pagination")
  void bitbucketUsesBearerTokenAndBoundsPagination() throws Exception {
    when(http.get(any(), any(), any())).thenReturn(
      response(
        "{\"values\":[{\"id\":\"refs/tags/v1\",\"displayId\":\"v1\",\"latestCommit\":\"abc\"}],\"isLastPage\":false,\"nextPageStart\":50}"
      )
    );
    final var references = new BitbucketReferences(catalog(), config("token"), resolver, http);
    final var result = references.list(
      "receiver",
      "tags",
      0,
      new RequestAuthentication(null, null, "private-token")
    );
    assertThat(result.nextStart()).isEqualTo(50);
    assertThat(result.values().getFirst().id()).isEqualTo("refs/tags/v1");
    verify(http).get(
      URI.create(
        "https://stash.invalid/rest/api/1.0/projects/SP/repos/receiver/tags?limit=50&start=0"
      ),
      "Bearer private-token",
      "application/json"
    );
    assertThat(Json.write(result)).doesNotContain("private-token");
    assertThatThrownBy(() ->
      references.list("receiver", "../secrets", 0, null)
    ).hasMessageContaining("branches or tags");
    when(http.get(any(), any(), any())).thenReturn(
      response("{\"values\":[],\"isLastPage\":false,\"nextPageStart\":0}")
    );
    assertThatThrownBy(() ->
      references.list("receiver", "tags", 0, new RequestAuthentication(null, null, "private-token"))
    ).hasMessageContaining("Unexpected Bitbucket");
  }

  /**
   * <b>Scenario:</b> Credential References Can Resolve Tokens And Cannot Be Overridden By Request Credentials
   * <pre>
   * GIVEN ... a configured Secret Server token reference
   * WHEN ... authorization is resolved or request credentials try to override it
   * THEN ... the referenced bearer token is used and overrides or header injection are rejected
   * </pre>
   */
  @Test
  @DisplayName(
    "Credential References Can Resolve Tokens And Cannot Be Overridden By Request Credentials"
  )
  void credentialReferencesCanResolveTokensAndCannotBeOverriddenByRequestCredentials() {
    when(resolver.resolve("vault-token")).thenReturn(
      new CredentialResolver.Secret(null, "access-token", true)
    );
    assertThat(
      RequestAuthentication.authorization("secret-server", "vault-token", null, resolver)
    ).isEqualTo("Bearer access-token");
    assertThatThrownBy(() ->
      RequestAuthentication.authorization(
        "secret-server",
        "vault-token",
        new RequestAuthentication("user", "password", null),
        resolver
      )
    ).hasMessageContaining("configured credential");
    assertThatThrownBy(() ->
      RequestAuthentication.bearer("token\r\nInjected: header")
    ).hasMessageContaining("valid bearer token");
  }

  /**
   * <b>Scenario:</b> Supplied Vault Tokens Are Session Scoped Expire And Never Appear In Status
   * <pre>
   * GIVEN ... a browser session with a supplied vault token
   * WHEN ... authorization and public status are read before and after expiry
   * THEN ... the token stays session-scoped, expires, and never appears in status
   * </pre>
   */
  @Test
  @DisplayName("Supplied Vault Tokens Are Session Scoped Expire And Never Appear In Status")
  void suppliedVaultTokensAreSessionScopedExpireAndNeverAppearInStatus() {
    final var config = new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(),
        Map.of(
          "vault",
          new ConnectionConfig.SecretServer(
            "https://vault.invalid/api/v1",
            null,
            "token",
            null,
            null
          )
        ),
        Map.of(),
        Map.of()
      )
    );
    final var now = Instant.parse("2026-09-29T12:00:00Z");
    final var tokens = new SecretServerTokens(
      config,
      http,
      (final var key) -> null,
      Clock.fixed(now, ZoneOffset.UTC)
    );
    final var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    final var status = tokens.useToken("vault", "private-token", 60, request.getSession());
    assertThat(status).containsEntry("state", "TOKEN_PROVIDED");
    assertThat(Json.write(status)).doesNotContain("private-token");
    assertThat(tokens.authorization("vault")).isEqualTo("Bearer private-token");
    assertThat(tokens.status("vault", new MockHttpSession())).containsEntry(
      "state",
      "SIGN_IN_REQUIRED"
    );
    final var expired = new SecretServerTokens(
      config,
      http,
      (final var key) -> null,
      Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
    );
    assertThatThrownBy(() -> expired.authorization("vault")).hasMessageContaining("Sign in");
    assertThatThrownBy(() ->
      tokens.useToken("vault", "private-token", 28801, request.getSession())
    ).hasMessageContaining("at most 8 hours");
    verifyNoInteractions(http);
  }

  /**
   * <b>Scenario:</b> Vault Can Bootstrap From Another Vault Token Without Caching The Secret
   * <pre>
   * GIVEN ... a vault authenticated through another vault&#x27;s secret reference
   * WHEN ... authorization is requested using the bootstrap session
   * THEN ... the referenced secret supplies authorization without caching the resolved secret
   * </pre>
   */
  @Test
  @DisplayName("Vault Can Bootstrap From Another Vault Token Without Caching The Secret")
  void vaultCanBootstrapFromAnotherVaultTokenWithoutCachingTheSecret() {
    final var credential = new ConnectionConfig.Credential(
      "delinea",
      null,
      null,
      "root",
      "123",
      null,
      null,
      "access-token",
      null
    );
    final var config = new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(),
        Map.of(
          "root",
          new ConnectionConfig.SecretServer("https://root.invalid/api/v1", "ROOT_TOKEN"),
          "child",
          new ConnectionConfig.SecretServer(
            "https://child.invalid/api/v1",
            null,
            "secret-server",
            null,
            null,
            "child-token"
          )
        ),
        Map.of(
          "child-token",
          credential,
          "registry",
          new ConnectionConfig.Credential(
            "delinea",
            null,
            null,
            "child",
            "456",
            "username",
            "password"
          )
        ),
        Map.of()
      )
    );
    when(
      http.get(
        URI.create("https://root.invalid/api/v1/secrets/123"),
        "Bearer root-token",
        "application/json"
      )
    ).thenReturn(
      response("{\"items\":[{\"slug\":\"access-token\",\"itemValue\":\"child-token-value\"}]}")
    );
    when(
      http.get(
        URI.create("https://child.invalid/api/v1/secrets/456"),
        "Bearer child-token-value",
        "application/json"
      )
    ).thenReturn(
      response(
        "{\"items\":[{\"slug\":\"username\",\"itemValue\":\"user\"},{\"slug\":\"password\",\"itemValue\":\"password\"}]}"
      )
    );
    final var credentials = new ConfiguredCredentials(
      config,
      http,
      (final var name) -> "root-token"
    );
    assertThat(credentials.resolve("registry").password()).isEqualTo("password");
    credentials.resolve("registry");
    verify(http, times(2)).get(
      URI.create("https://root.invalid/api/v1/secrets/123"),
      "Bearer root-token",
      "application/json"
    );
  }

  /**
   * <b>Scenario:</b> Rejects Password Authentication Even For Legacy Ad Configuration
   * <pre>
   * GIVEN ... a registry using legacy AD configuration or a password-only secret
   * WHEN ... token authorization is requested
   * THEN ... password authentication is rejected without an HTTP call
   * </pre>
   */
  @Test
  @DisplayName("Rejects Password Authentication Even For Legacy Ad Configuration")
  void rejectsPasswordAuthenticationEvenForLegacyAdConfiguration() {
    assertThat(config("ad").data().artifactory().get("registry").mode()).isEqualTo("token");
    assertThatThrownBy(() ->
      RequestAuthentication.authorization(
        "token",
        null,
        new RequestAuthentication("alice", "password", null),
        resolver
      )
    ).hasMessageContaining("only a token");
    when(resolver.resolve("password-reference")).thenReturn(
      new CredentialResolver.Secret("alice", "password")
    );
    assertThatThrownBy(() ->
      RequestAuthentication.authorization("secret-server", "password-reference", null, resolver)
    ).hasMessageContaining("token credential");
    verifyNoInteractions(http);
  }

  /**
   * <b>Scenario:</b> Circular Vault Authentication Is Rejected At Configuration Time
   * <pre>
   * GIVEN ... a vault whose bootstrap credential refers back to itself
   * WHEN ... connection configuration is constructed
   * THEN ... validation rejects the authentication cycle
   * </pre>
   */
  @Test
  @DisplayName("Circular Vault Authentication Is Rejected At Configuration Time")
  void circularVaultAuthenticationIsRejectedAtConfigurationTime() {
    final var server = new ConnectionConfig.SecretServer(
      "https://vault.invalid/api/v1",
      null,
      "secret-server",
      null,
      null,
      "bootstrap"
    );
    final var credential = new ConnectionConfig.Credential(
      "delinea",
      null,
      null,
      "vault",
      "123",
      null,
      null,
      "token",
      null
    );
    assertThatThrownBy(() ->
      new ConnectionConfig(
        new ConnectionConfig.Data(
          Map.of(),
          Map.of("vault", server),
          Map.of("bootstrap", credential),
          Map.of()
        )
      )
    ).hasMessageContaining("cycle");
  }
}
