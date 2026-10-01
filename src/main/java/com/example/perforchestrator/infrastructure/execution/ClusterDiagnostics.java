package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.util.Locale;

/** Fixed explanations only: Kubernetes errors can include credentials or rendered values. */
public final class ClusterDiagnostics {
  private ClusterDiagnostics() {}
  public static Problem failure(String operation,int exit,String output) {
    String text=output==null?"":output.toLowerCase(Locale.ROOT);
    String reason;
    if(text.contains("unknown flag") || text.contains("unknown shorthand flag"))
      reason="The installed Helm version rejected a command option. Check helm version --short and update the application for that Helm version; this is not a cluster authentication failure.";
    else if(text.contains("unauthorized") || text.contains("provide credentials") || text.contains("must be logged in") || text.contains("authentication required"))
      reason="Cluster authentication is missing or expired. Renew oc/kubectl login for the application's kube-context. Secret Server login does not authenticate Helm to CKP.";
    else if(text.contains("forbidden") || text.contains("cannot list resource") || text.contains("cannot get resource"))
      reason="The cluster identity lacks permission to read Helm release records in this namespace. Check Kubernetes RBAC (normally get/list Secrets for Helm's default storage).";
    else if(text.contains("x509") || text.contains("certificate"))
      reason="Cluster TLS verification failed. Check the kubeconfig CA and API-server hostname; do not disable certificate verification.";
    else if(text.contains("exec:") || text.contains("executable") || text.contains("exec plugin") || text.contains("getting credentials"))
      reason="The kubeconfig credential plugin failed or is unavailable. Ensure its executable and login configuration are available to the application process.";
    else if(text.contains("timeout") || text.contains("timed out") || text.contains("connection refused") || text.contains("no such host") || text.contains("network is unreachable"))
      reason="The application cannot reach the Kubernetes API. Check VPN, DNS, network access and the configured API-server URL.";
    else if(text.contains("namespace") && text.contains("not found"))
      reason="The target namespace does not exist or is unavailable to the cluster identity.";
    else reason="Check the same Helm command locally using the application's kube-context, namespace and KUBECONFIG. Verify cluster login, network access and permission to read Helm release records.";
    return new Problem(502,"HELM_CLUSTER_LOOKUP_FAILED","execution",operation+" failed (exit "+exit+"). "+reason+" Raw output is withheld.");
  }
}
