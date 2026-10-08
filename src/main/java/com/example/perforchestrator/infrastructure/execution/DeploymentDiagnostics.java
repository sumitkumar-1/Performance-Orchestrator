package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.util.Locale;

/** Classifies Helm deployment failures without exposing rendered values or credentials. */
public final class DeploymentDiagnostics {

  private DeploymentDiagnostics() {}

  public static Problem failure(final String operation, final int exit, final String output) {
    final String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
    final String reason;
    if (text.contains("forbidden")) reason =
      "Kubernetes denied an operation. Check deployment permissions and admission/security policies in the target namespace; release-read access alone is insufficient.";
    else if (
      text.contains("unauthorized") ||
      text.contains("must be logged in") ||
      text.contains("getting credentials")
    ) reason =
      "Cluster authentication failed or expired. Renew the application's CKP login; Secret Server authentication is separate.";
    else if (text.contains("another operation") && text.contains("in progress")) reason =
      "Another Helm operation is pending for this release. Inspect helm status/history and resolve that operation before preparing again.";
    else if (
      text.contains("invalid ownership") ||
      text.contains("cannot be imported") ||
      text.contains("already exists")
    ) reason =
      "An existing release or Kubernetes resource conflicts with this deployment. Inspect resource ownership and release naming before retrying.";
    else if (
      text.contains("hook") && (text.contains("failed") || text.contains("timed out"))
    ) reason =
      "A Helm hook failed or did not complete. Inspect hook Jobs, their pods and namespace events.";
    else if (
      text.contains("timed out waiting") ||
      text.contains("context deadline exceeded") ||
      text.contains("timeout")
    ) reason =
      "The Helm operation exceeded its deadline. Resources may have been applied but not become ready. Inspect pods, events, image pulls, probes and scheduling; also check API connectivity before changing the timeout.";
    else if (text.contains("immutable") || text.contains("cannot patch")) reason =
      "Kubernetes rejected an update to an existing resource. Check immutable fields and the chart changes; the application does not force resource replacement.";
    else if (text.contains("no matches for kind") || text.contains("ensure crds")) reason =
      "A required Kubernetes API kind or CRD is unavailable. Check cluster compatibility and required CRD installation.";
    else if (
      text.contains("is invalid") ||
      text.contains("admission webhook") ||
      text.contains("failed calling webhook")
    ) reason =
      "Kubernetes validation or an admission webhook rejected the deployment. Check chart values, namespace events and cluster policy.";
    else if (
      text.contains("missing in charts/") || text.contains("missing these dependencies")
    ) reason =
      "Helm reported missing chart dependencies in the prepared snapshot. Prepare a new run and check dependency preparation.";
    else reason =
      "Helm returned an unclassified deployment error. Inspect helm status/history, namespace events and workload readiness using the same kube-context and namespace. Resources may already have been applied.";
    return new Problem(
      422,
      "HELM_DEPLOYMENT_FAILED",
      "execution",
      operation +
        " failed (exit " +
        exit +
        "). " +
        reason +
        " Raw output is withheld because it may contain secrets."
    );
  }
}
