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
    public Optional<String> header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .flatMap(e -> e.getValue().stream())
          .findFirst();
    }
  }

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public Response get(URI uri, String authorization, String accept) {
    ConnectionConfig.base(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getPath());
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12))
            .header("Authorization", authorization)
            .header("Accept", accept)
            .GET()
            .build();
    return send(request);
  }

  /** Only called for the administrator-configured Secret Server token endpoint. */
  public Response tokenForm(URI uri, String body) {
    ConnectionConfig.base(uri.toString());
    return send(
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private Response send(HttpRequest request) {
    CompletableFuture<HttpResponse<byte[]>> future =
        client.sendAsync(request, info -> new LimitedBody(2 * 1024 * 1024));
    try {
      var response = future.get(15, TimeUnit.SECONDS);
      return new Response(response.statusCode(), response.headers().map(), response.body());
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new Problem(
          503, "SOURCE_UNAVAILABLE", "connection", "Read-only connection interrupted");
    } catch (ExecutionException | TimeoutException e) {
      future.cancel(true);
      throw new Problem(
          503,
          "SOURCE_UNAVAILABLE",
          "connection",
          "Read-only connection timed out or was unavailable; verify server, TLS and credentials");
    }
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private long size;
    private Flow.Subscription subscription;

    LimitedBody(long limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) size += buffer.remaining();
      if (size > limit) {
        subscription.cancel();
        delegate.onError(new IllegalStateException("Response too large"));
      } else delegate.onNext(buffers);
    }

    public void onError(Throwable error) {
      delegate.onError(error);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }

  public static void requireSuccess(Response response, String field) {
    if (response.status() == 401 || response.status() == 403)
      throw new Problem(
          403,
          "SOURCE_DENIED",
          field,
          "Read-only access denied; verify the configured credential reference and permissions");
    if (response.status() == 404)
      throw new Problem(
          404, "SOURCE_NOT_FOUND", field, "Registered source, secret or image is unavailable");
    if (response.status() < 200 || response.status() >= 300)
      throw new Problem(
          503,
          "SOURCE_UNAVAILABLE",
          field,
          "Registered source did not return a successful response");
  }
}
