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
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class ConnectionAuthenticationTest {
  final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
  final CredentialResolver resolver = mock(CredentialResolver.class);
  ReadOnlyHttp.Response response(String json) { return new ReadOnlyHttp.Response(200, Map.of(), json.getBytes(StandardCharsets.UTF_8)); }
  ConnectionConfig config(String mode) {
    return new ConnectionConfig(new ConnectionConfig.Data(
        Map.of("registry", new ConnectionConfig.Artifactory("https://registry.invalid/artifactory/api/docker", null, mode)),
        Map.of(), Map.of(), Map.of(), Map.of("stash", new ConnectionConfig.Bitbucket("https://stash.invalid/rest/api", null, mode))));
  }
  Catalog catalog() throws Exception {
    var catalog = new Catalog("", "real", "127.0.0.1");
    var service = new Catalog.Service("projects/receiver", List.of(), Map.of(), null, List.of(),
        new Catalog.Destination("receiver", "receiver", List.of("ckp/helm/values.yaml")),
        new Catalog.ContainerImage("registry", "stable", "ps-spoolers", "ps-spoolers-sng-smtp-receiver"),
        new Catalog.SourceProject("stash", "SP", "receiver", "v1", "ckp/helm/receiver"));
    catalog.replace(new Catalog.Data("real", Map.of(), Map.of("receiver", service), Map.of(), Map.of()));
    return catalog;
  }
  @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

  @Test void registryUsesServiceStageAndImagePathWithRequestOnlyBasicCredentials() throws Exception {
    var config = config("ad");
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(http.get(any(), any(), any())).thenReturn(response("{\"tags\":[\"v1\"]}"));
    var images = new ArtifactoryImages(config, http, resolver, catalog());
    var input = new RequestAuthentication("ad-user", "private-password", null);
    assertThat(images.discover("receiver", "service:receiver", null, "", 50, input).versions()).containsExactly("v1");
    verify(http).get(URI.create("https://registry.invalid/artifactory/api/docker/docker-stable/v2/ps-spoolers/ps-spoolers-sng-smtp-receiver/tags/list?n=50"),
        RequestAuthentication.basic("ad-user", "private-password"), "application/json");
    assertThat(request.getSession(false)).isNull();
    assertThat(Json.write(config.data())).doesNotContain("ad-user", "private-password");
    assertThat(input.toString()).isEqualTo("[REDACTED]");
    assertThatThrownBy(() -> images.discover("receiver", "service:receiver", null, "", 50)).hasMessageContaining("Sign in");
    verifyNoInteractions(resolver);
  }

  @Test void bitbucketUsesBearerTokenAndBoundsPagination() throws Exception {
    when(http.get(any(), any(), any())).thenReturn(response("{\"values\":[{\"id\":\"refs/tags/v1\",\"displayId\":\"v1\",\"latestCommit\":\"abc\"}],\"isLastPage\":false,\"nextPageStart\":50}"));
    var references = new BitbucketReferences(catalog(), config("token"), resolver, http);
    var result = references.list("receiver", "tags", 0, new RequestAuthentication(null, null, "private-token"));
    assertThat(result.nextStart()).isEqualTo(50);
    assertThat(result.values().getFirst().id()).isEqualTo("refs/tags/v1");
    verify(http).get(URI.create("https://stash.invalid/rest/api/1.0/projects/SP/repos/receiver/tags?limit=50&start=0"), "Bearer private-token", "application/json");
    assertThat(Json.write(result)).doesNotContain("private-token");
    assertThatThrownBy(() -> references.list("receiver", "../secrets", 0, null)).hasMessageContaining("branches or tags");
    when(http.get(any(), any(), any())).thenReturn(response("{\"values\":[],\"isLastPage\":false,\"nextPageStart\":0}"));
    assertThatThrownBy(() -> references.list("receiver", "tags", 0, new RequestAuthentication(null, null, "private-token"))).hasMessageContaining("Unexpected Bitbucket");
  }

  @Test void credentialReferencesCanResolveTokensAndCannotBeOverriddenByRequestCredentials() {
    when(resolver.resolve("vault-token")).thenReturn(new CredentialResolver.Secret(null, "access-token", true));
    assertThat(RequestAuthentication.authorization("secret-server", "vault-token", null, resolver)).isEqualTo("Bearer access-token");
    assertThatThrownBy(() -> RequestAuthentication.authorization("secret-server", "vault-token", new RequestAuthentication("user", "password", null), resolver)).hasMessageContaining("configured credential");
    assertThatThrownBy(() -> RequestAuthentication.bearer("token\r\nInjected: header")).hasMessageContaining("valid bearer token");
  }

  @Test void suppliedVaultTokensAreSessionScopedExpireAndNeverAppearInStatus() {
    var config = new ConnectionConfig(new ConnectionConfig.Data(Map.of(), Map.of("vault",
        new ConnectionConfig.SecretServer("https://vault.invalid/api/v1", null, "token", null, null)), Map.of(), Map.of()));
    var now = Instant.parse("2026-09-29T12:00:00Z");
    var tokens = new SecretServerTokens(config, http, key -> null, Clock.fixed(now, ZoneOffset.UTC));
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    var status = tokens.useToken("vault", "private-token", 60, request.getSession());
    assertThat(status).containsEntry("state", "TOKEN_PROVIDED");
    assertThat(Json.write(status)).doesNotContain("private-token");
    assertThat(tokens.authorization("vault")).isEqualTo("Bearer private-token");
    assertThat(tokens.status("vault", new MockHttpSession())).containsEntry("state", "SIGN_IN_REQUIRED");
    var expired = new SecretServerTokens(config, http, key -> null, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
    assertThatThrownBy(() -> expired.authorization("vault")).hasMessageContaining("Sign in");
    assertThatThrownBy(() -> tokens.useToken("vault", "private-token", 28801, request.getSession())).hasMessageContaining("at most 8 hours");
    verifyNoInteractions(http);
  }

  @Test void vaultCanBootstrapFromAnotherVaultTokenWithoutCachingTheSecret() {
    var credential = new ConnectionConfig.Credential("delinea", null, null, "root", "123", null, null, "access-token", null);
    var config = new ConnectionConfig(new ConnectionConfig.Data(Map.of(), Map.of(
        "root", new ConnectionConfig.SecretServer("https://root.invalid/api/v1", "ROOT_TOKEN"),
        "child", new ConnectionConfig.SecretServer("https://child.invalid/api/v1", null, "secret-server", null, null, "child-token")),
        Map.of("child-token", credential, "registry", new ConnectionConfig.Credential("delinea", null, null, "child", "456", "username", "password")), Map.of()));
    when(http.get(URI.create("https://root.invalid/api/v1/secrets/123"), "Bearer root-token", "application/json"))
        .thenReturn(response("{\"items\":[{\"slug\":\"access-token\",\"itemValue\":\"child-token-value\"}]}"));
    when(http.get(URI.create("https://child.invalid/api/v1/secrets/456"), "Bearer child-token-value", "application/json"))
        .thenReturn(response("{\"items\":[{\"slug\":\"username\",\"itemValue\":\"user\"},{\"slug\":\"password\",\"itemValue\":\"password\"}]}"));
    var credentials = new ConfiguredCredentials(config, http, name -> "root-token");
    assertThat(credentials.resolve("registry").password()).isEqualTo("password");
    credentials.resolve("registry");
    verify(http, times(2)).get(URI.create("https://root.invalid/api/v1/secrets/123"), "Bearer root-token", "application/json");
  }

  @Test void vaultCanExchangeReferencedAdCredentialsWithoutRetainingThemOrTheChildToken() {
    var credential = new ConnectionConfig.Credential("environment", "VAULT_USER", "VAULT_PASSWORD", null, null, null, null);
    var config = new ConnectionConfig(new ConnectionConfig.Data(Map.of(), Map.of("child",
        new ConnectionConfig.SecretServer("https://child.invalid/api/v1", null, "secret-server", "https://child.invalid/oauth2/token", null, "bootstrap")),
        Map.of("bootstrap", credential), Map.of()));
    when(http.tokenForm(any(), any())).thenReturn(response("{\"access_token\":\"child-token\",\"expires_in\":60}"));
    var tokens = new SecretServerTokens(config, http);
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    for (int i = 0; i < 2; i++) assertThat(tokens.authorization("child", ref -> new CredentialResolver.Secret("ad-user", "password")))
        .isEqualTo("Bearer child-token");
    assertThat(request.getSession(false)).isNull();
    verify(http, times(2)).tokenForm(URI.create("https://child.invalid/oauth2/token"), "grant_type=password&username=ad-user&password=password");
  }

  @Test void circularVaultAuthenticationIsRejectedAtConfigurationTime() {
    var server = new ConnectionConfig.SecretServer("https://vault.invalid/api/v1", null, "secret-server", null, null, "bootstrap");
    var credential = new ConnectionConfig.Credential("delinea", null, null, "vault", "123", null, null, "token", null);
    assertThatThrownBy(() -> new ConnectionConfig(new ConnectionConfig.Data(Map.of(), Map.of("vault", server), Map.of("bootstrap", credential), Map.of())))
        .hasMessageContaining("cycle");
  }
}
