package com.example.perforchestrator.infrastructure.diagnostics;

import java.net.URI;
import java.util.*;

/** Allowlisted summaries, never a best-effort scrub of credentials or arbitrary output. */
public final class SafeDiagnostics {
  private SafeDiagnostics() {}
  private static final Set<String> WORDS=Set.of("helm","git","kubectl","version","config","view","list","status","lint","template","install","upgrade","uninstall","repo","add","dependency","build","update","init","remote","sparse-checkout","set","fetch","rev-parse","checkout","json",".");
  private static final Set<String> FLAGS=Set.of("--kube-context","--context","--namespace","--filter","--output","--values","--description","--timeout","--username","--password-stdin","--force-update","--all","--wait","--install","--minify","--no-cone","--detach","--verify","--kubeconfig","-o","--template","--request-timeout","--depth","--no-tags");
  public static String command(List<String> args){
    List<String> result=new ArrayList<>();
    Set<String> valueFlags=Set.of("--kube-context","--context","--namespace","--filter","--output","--values","--description","--timeout","--username","--password","--kubeconfig","-o","--template","--request-timeout","--depth","--set","--set-string");
    boolean redactNext=false;
    for(String arg:args){String flag=arg.contains("=")?arg.substring(0,arg.indexOf('=')):arg;
      if(redactNext){result.add("[redacted]");redactNext=false;continue;}
      if(WORDS.contains(arg))result.add(arg);
      else if(FLAGS.contains(flag))result.add(flag+(arg.contains("=")?"=[redacted]":""));
      else result.add("[redacted]");
      redactNext=valueFlags.contains(flag)&&!arg.contains("=");
    }
    return String.join(" ",result);
  }
  public static String endpoint(String method,URI uri){
    Set<String> segments=Set.of("artifactory","api","docker","v1","v2","rest","1.0","projects","repos","branches","tags","list","manifests","SecretServer","secrets","oauth2","token","loki","query","query_range");
    String path=Arrays.stream(uri.getPath().split("/",-1)).filter(s->!s.isEmpty()).map(s->segments.contains(s)?s:"[redacted]").reduce("",(a,b)->a+"/"+b);
    return method+" "+uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+path+(uri.getRawQuery()==null?"":"?[redacted]");
  }
}
