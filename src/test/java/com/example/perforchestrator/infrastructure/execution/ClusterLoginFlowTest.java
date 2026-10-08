package com.example.perforchestrator.infrastructure.execution;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.secrets.AdSessionCredentials;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

class ClusterLoginFlowTest {

  @TempDir
  Path directory;

  private MockHttpServletRequest request;

  @BeforeEach
  void givenAnAuthenticatedBrowserSession() {
    request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    AdSessionCredentials.remember(
      request.getSession(),
      "first.last@example.net",
      "private-password",
      Instant.now().plusSeconds(60)
    );
  }

  @AfterEach
  void clearSession() {
    request.getSession().invalidate();
    RequestContextHolder.resetRequestAttributes();
  }

  /**
   * <b>Scenario:</b> Given Ad Session When Cluster Prompts Then Authenticate Without Password Arguments
   * <pre>
   * GIVEN ... an AD-authenticated browser and a command prompting for credentials
   * WHEN ... the command runner handles username and password prompts
   * THEN ... credentials are sent through stdin, excluded from arguments, and redacted from output
   * </pre>
   */
  @Test
  @DisplayName(
    "Given an AD session, when a cluster helper prompts, then stdin supplies credentials and output redacts the password"
  )
  void givenAdSessionWhenClusterPromptsThenAuthenticateWithoutPasswordArguments() throws Exception {
    // Given a real subprocess with the same prompt contract as the cluster helper.
    final Path helper = directory.resolve("helm");
    Files.writeString(
      helper,
      "#!/bin/sh\nprintf 'Username: '\nread -r user\nprintf 'Password: '\nread -r password\n[ \"$user\" = 'first.last@example.net' ] || exit 3\n[ \"$password\" = 'private-password' ] || exit 4\nprintf 'authenticated %s' \"$password\"\n"
    );
    assertThat(helper.toFile().setExecutable(true)).isTrue();
    final var arguments = List.of(helper.toString(), "--kube-context", "sandbox-nvan", "list");
    // When Helm requests credentials through its helper.
    final var result = new CommandRunner().run(
      arguments,
      directory,
      Map.of(),
      Duration.ofSeconds(5)
    );
    // Then the actual process succeeds without receiving credentials in its argv or returning them.
    assertThat(result.exit()).isZero();
    assertThat(result.output())
      .contains("authenticated [REDACTED]")
      .doesNotContain("private-password");
    assertThat(arguments).noneMatch((final var argument) -> argument.contains("private-password"));
  }

  /**
   * <b>Scenario:</b> Given Session Bound Run When Session Ends Then Credentials Are Destroyed
   * <pre>
   * GIVEN ... a run bound to encrypted credentials in one browser session
   * WHEN ... another session requests access and the owner invalidates its session
   * THEN ... cross-session access is denied and retained credentials become unavailable
   * </pre>
   */
  @Test
  @DisplayName(
    "Given two browser sessions, when one signs out, then its run loses credentials and the other session cannot read them"
  )
  void givenSessionBoundRunWhenSessionEndsThenCredentialsAreDestroyed() throws Exception {
    // Given a run attached to one browser's credentials.
    AdSessionCredentials.attach("session-test-run");
    RequestContextHolder.setRequestAttributes(
      new ServletRequestAttributes(new MockHttpServletRequest())
    );
    assertThat(AdSessionCredentials.open()).isNull();
    // When the worker uses the run's handle it can authenticate, until the original session ends.
    try (final var scope = AdSessionCredentials.scope("session-test-run")) {
      try (final var plaintext = AdSessionCredentials.open()) {
        assertThat(new String(plaintext.response(false), StandardCharsets.UTF_8)).isEqualTo(
          "first.last@example.net\n"
        );
      }
      request.getSession().invalidate();
      // Then even the captured run handle can no longer decrypt credentials.
      assertThat(AdSessionCredentials.open()).isNull();
    }
  }

  /**
   * <b>Scenario:</b> Given Expired Session When Credentials Requested Then Reject
   * <pre>
   * GIVEN ... AD credentials whose session lifetime has expired
   * WHEN ... cluster login credentials are requested
   * THEN ... no credentials are returned
   * </pre>
   */
  @Test
  @DisplayName(
    "Given expired AD credentials, when cluster authentication needs them, then they are unavailable"
  )
  void givenExpiredSessionWhenCredentialsRequestedThenReject() {
    AdSessionCredentials.remember(
      request.getSession(),
      "user",
      "secret",
      Instant.now().minusSeconds(1)
    );
    assertThat(AdSessionCredentials.open()).isNull();
  }
}
