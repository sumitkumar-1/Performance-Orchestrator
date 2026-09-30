package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.nio.file.*;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reports fixed diagnostic categories and existing source-file locations, never Helm output text. */
public final class HelmDiagnostics {
  private HelmDiagnostics() {}

  public static Problem failure(String operation, int exit, String output, Path folder, String chart) {
    String text=output==null?"":output;
    String lower=text.toLowerCase(Locale.ROOT);
    String reason;
    if(lower.contains("missing in charts/") || lower.contains("missing these dependencies") || lower.contains("found in chart.yaml, but missing"))
      reason="Missing packaged chart dependencies. The selected Git revision must include dependencies under the chart's charts/ directory. This application does not run helm dependency build/update.";
    else if(lower.contains("schema") && (lower.contains("values") || lower.contains("validation")))
      reason="Values do not satisfy the chart's JSON schema. Check required fields, types and permitted formats, including support for digest-qualified image tags.";
    else if(lower.contains("nil pointer") || lower.contains("can't evaluate field") || lower.contains("cannot evaluate field"))
      reason="A template references a missing value or an unexpected value type. Check selected values files and required organization-specific --set values.";
    else if(lower.contains("required") || lower.contains("execution error"))
      reason="The chart rejected a required value or a template validation rule. Compare the selected YAML with the values and --set arguments used in a successful deployment.";
    else if(lower.contains("yaml parse") || lower.contains("unable to parse yaml") || lower.contains("error converting yaml") || lower.contains("did not find expected"))
      reason="Chart rendering produced invalid YAML. Inspect the indicated template and its input values.";
    else if(lower.contains("parse error") || lower.contains("unexpected eof") || lower.contains("function") && lower.contains("not defined"))
      reason="Helm could not parse a template. Check template syntax, helper definitions and the Helm version used by the application.";
    else if(lower.contains("chart.yaml") && (lower.contains("missing") || lower.contains("version") || lower.contains("required")))
      reason="Helm rejected chart metadata. Check Chart.yaml in the selected revision.";
    else reason="Helm validation failed. Run helm lint locally with the same Git revision, namespace, values files and overrides to inspect the full diagnostic.";
    String location=location(text,folder,chart);
    return new Problem(422,"HELM_"+operation.toUpperCase(Locale.ROOT)+"_FAILED","execution",
        "Helm "+operation+" failed (exit "+exit+")"+(location.isEmpty()?"": " at "+location)+". "+reason
            +" Raw output is not included because template errors can contain secret values.");
  }

  private static String location(String text,Path folder,String chart) {
    Path root=folder.resolve(chart);
    try(var paths=Files.walk(root)) {
      for(Path path:paths.filter(Files::isRegularFile).sorted().toList()) {
        String relative=root.relativize(path).toString().replace('\\','/');
        if(!relative.matches("[A-Za-z0-9_./-]{1,250}"))continue;
        var match=Pattern.compile(Pattern.quote(relative)+":([0-9]{1,8})(?::[0-9]+)?").matcher(text);
        if(match.find())return chart+"/"+relative+":"+match.group(1);
      }
    }catch(java.io.IOException ignored){ /* Source location is optional. */ }
    return "";
  }
}
