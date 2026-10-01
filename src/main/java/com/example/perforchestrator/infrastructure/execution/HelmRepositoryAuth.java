package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class HelmRepositoryAuth {
  public static final class Credential {
    public final String username,token;
    Credential(String username,String token){this.username=username;this.token=token;}
    @Override public String toString(){return "Helm repository credential [redacted]";}
  }
  private final ConnectionConfig connections;
  private final ConnectionSessions sessions;
  public HelmRepositoryAuth(ConnectionConfig connections,ConnectionSessions sessions){this.connections=connections;this.sessions=sessions;}
  public Credential resolve(ExecutionSettings settings,String url) {
    if(settings.helmRepositoryConnection.isEmpty())return null;
    var connection=connections.data().artifactory().get(settings.helmRepositoryConnection);
    if(connection==null)throw Problem.invalid("helm-repository-connection","Choose an existing Artifactory connection");
    URI repo=URI.create(url), configured=URI.create(connection.apiBaseUrl());
    if(!repo.getHost().equalsIgnoreCase(configured.getHost()) || port(repo)!=port(configured))
      throw Problem.invalid("helm-repositories","Helm repository must use the configured Artifactory connection's HTTPS host and port before its token can be sent");
    String actor=settings.helmRepositoryUsername.isEmpty()?SecretServerTokens.currentActor():settings.helmRepositoryUsername;
    if(actor.equals("local-developer"))throw Problem.invalid("helm-repository-username","Set helm-repository-username to the Artifactory token owner's short username, or sign in to Secret Server using AD");
    String username=shortUsername(actor);
    String authorization=sessions.authorization("artifactory",settings.helmRepositoryConnection,null);
    if(!authorization.startsWith("Bearer "))throw Problem.invalid("helm-repository-connection","Helm repository authentication requires an Artifactory token reference or token session");
    return new Credential(username,authorization.substring(7));
  }
  public static String shortUsername(String value) {
    String name=value.strip();int slash=name.lastIndexOf('\\');if(slash>=0)name=name.substring(slash+1);
    int at=name.indexOf('@');if(at>=0)name=name.substring(0,at);
    if(!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))throw Problem.invalid("helm-repository-username","Use a valid short Artifactory username such as first.last");
    return name;
  }
  private static int port(URI uri){return uri.getPort()==-1?443:uri.getPort();}
}
