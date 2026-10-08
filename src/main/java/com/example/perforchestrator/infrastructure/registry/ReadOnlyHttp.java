package com.example.perforchestrator.infrastructure.registry;

import com.example.perforchestrator.domain.Problem;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/**
 * Bounded read transport with a dedicated token form exchange. Redirects never forward credentials
 * to another destination.
 */
@Component
public class ReadOnlyHttp {

  public record Response(int status, Map<String, List<String>> headers, byte[] body) {
    public Optional<String> header(final String name) {
      return headers
        .entrySet()
        .stream()
        .filter((final var e) -> e.getKey().equalsIgnoreCase(name))
        .flatMap((final var e) -> e.getValue().stream())
        .findFirst();
    }
  }

  private final HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();

  public Response get(final URI uri, final String authorization, final String accept) {
    ConnectionConfig.base(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getPath());
    final HttpRequest request = HttpRequest.newBuilder(uri)
      .timeout(Duration.ofSeconds(12))
      .header("Authorization", authorization)
      .header("Accept", accept)
      .GET()
      .build();
    return send(request);
  }

  public Response tokenForm(final URI uri, final String username, final String password) {
    ConnectionConfig.base(uri.toString());
    final String body =
      "grant_type=password&username=" +
      java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8) +
      "&password=" +
      java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
    return send(
      HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(12))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    );
  }

  private Response send(final HttpRequest request) {
    final var diagnostic =
      com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog.begin(
        "HTTP",
        com.example.perforchestrator.infrastructure.diagnostics.SafeDiagnostics.endpoint(
          request.method(),
          request.uri()
        )
      );
    try {
      final var response = sendRecorded(request);
      diagnostic.finish("HTTP " + response.status());
      return response;
    } catch (final RuntimeException error) {
      diagnostic.finish("REQUEST FAILED");
      throw error;
    }
  }

  private Response sendRecorded(final HttpRequest request) {
    final CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(
      request,
      (final var info) -> new LimitedBody(2 * 1024 * 1024)
    );
    try {
      final var response = future.get(15, TimeUnit.SECONDS);
      return new Response(response.statusCode(), response.headers().map(), response.body());
    } catch (final InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new Problem(
        503,
        "SOURCE_UNAVAILABLE",
        "connection",
        "Read-only connection interrupted"
      );
    } catch (ExecutionException | TimeoutException e) {
      future.cancel(true);
      throw new Problem(
        503,
        "SOURCE_UNAVAILABLE",
        "connection",
        "Read-only connection timed out or was unavailable; verify server, TLS and credentials"
      );
    }
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final HttpResponse.BodySubscriber<byte[]> delegate =
      HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private long size;
    private Flow.Subscription subscription;

    LimitedBody(final long limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(final Flow.Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    public void onNext(final List<ByteBuffer> buffers) {
      for (final var buffer : buffers) size += buffer.remaining();
      if (size > limit) {
        subscription.cancel();
        delegate.onError(new IllegalStateException("Response too large"));
      } else delegate.onNext(buffers);
    }

    public void onError(final Throwable error) {
      delegate.onError(error);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }

  public static void requireSuccess(final Response response, final String field) {
    if (response.status() == 401 || response.status() == 403) throw new Problem(
      403,
      "SOURCE_DENIED",
      field,
      "Read-only access denied; verify the configured credential reference and permissions"
    );
    if (response.status() == 404) throw new Problem(
      404,
      "SOURCE_NOT_FOUND",
      field,
      "Registered source, secret or image is unavailable"
    );
    if (response.status() < 200 || response.status() >= 300) throw new Problem(
      503,
      "SOURCE_UNAVAILABLE",
      field,
      "Registered source did not return a successful response"
    );
  }
}
