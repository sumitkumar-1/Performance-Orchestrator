package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.util.Locale;

/** Fixed explanations only: Kubernetes errors can include credentials or rendered values. */
public final class ClusterDiagnostics {

  private ClusterDiagnostics() {}

  public static Problem failure(final String operation, final int exit, final String output) {
    final String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
    final String reason;
    if (text.contains("unknown flag") || text.contains("unknown shorthand flag")) reason =
      "The installed Helm version rejected a command option. Check helm version --short and update the application for that Helm version; this is not a cluster authentication failure.";
    else if (
      text.contains("unauthorized") ||
      text.contains("provide credentials") ||
      text.contains("must be logged in") ||
      text.contains("authentication required")
    ) reason =
      "Cluster authentication is missing or expired. Renew oc/kubectl login for the application's kube-context. Secret Server login does not authenticate Helm to CKP.";
    else if (
      text.contains("forbidden") ||
      text.contains("cannot list resource") ||
      text.contains("cannot get resource")
    ) reason =
      "The cluster identity lacks permission to read Helm release records in this namespace. Check Kubernetes RBAC (normally get/list Secrets for Helm's default storage).";
    else if (text.contains("x509") || text.contains("certificate")) reason =
      "Cluster TLS verification failed. Check the kubeconfig CA and API-server hostname; do not disable certificate verification.";
    else if (
      text.contains("exec:") ||
      text.contains("executable") ||
      text.contains("exec plugin") ||
      text.contains("getting credentials")
    ) reason =
      pluginHint(output) +
      " Check kubeconfig users[].user.exec.command and exec.apiVersion; the application must inherit the same PATH, KUBECONFIG and login environment as your working terminal.";
    else if (
      text.contains("timeout") ||
      text.contains("timed out") ||
      text.contains("connection refused") ||
      text.contains("no such host") ||
      text.contains("network is unreachable")
    ) reason =
      "The application cannot reach the Kubernetes API. Check VPN, DNS, network access and the configured API-server URL.";
    else if (text.contains("namespace") && text.contains("not found")) reason =
      "The target namespace does not exist or is unavailable to the cluster identity.";
    else reason =
      "Check the same Helm command locally using the application's kube-context, namespace and KUBECONFIG. Verify cluster login, network access and permission to read Helm release records.";
    return new Problem(
      502,
      "HELM_CLUSTER_LOOKUP_FAILED",
      "execution",
      operation + " failed (exit " + exit + "). " + reason + " Raw output is withheld."
    );
  }

  public static String pluginHint(final String output) {
    final String text = output == null ? "" : output;
    final var match = java.util.regex.Pattern.compile(
      "(?i)executable ([A-Za-z0-9_./\\\\:-]{1,250}) (not found|failed with exit code (-?[0-9]{1,3}))"
    ).matcher(text);
    if (match.find()) {
      String name = match.group(1).replace('\\', '/');
      name = name.substring(name.lastIndexOf('/') + 1);
      if (name.matches("[A-Za-z0-9_.-]{1,80}")) return (
        "Kubeconfig credential helper " +
        name +
        (match.group(2).equalsIgnoreCase("not found")
          ? " was not found on the application's PATH."
          : " exited with code " + match.group(3) + ".")
      );
    }
    final String lower = text.toLowerCase(Locale.ROOT);
    if (
      lower.contains("invalid apiversion") ||
      (lower.contains("no kind") && lower.contains("execcredential"))
    ) return "The kubeconfig credential plugin uses an unsupported ExecCredential API version.";
    if (
      lower.contains("interactivemode")
    ) return "The kubeconfig credential plugin's interactiveMode setting is missing or incompatible.";
    return "The kubeconfig credential plugin failed or is unavailable.";
  }
}
