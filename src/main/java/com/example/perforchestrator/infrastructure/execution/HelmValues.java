package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Helm values permit null (to remove defaults), unlike the simulation overlay format. */
public final class HelmValues {
  private HelmValues(){}
  public static Map<String,Object> parse(String text) {
    if(text==null || text.isBlank())return new LinkedHashMap<>();
    if(text.length()>65536)throw Problem.invalid("values","Values are limited to 64 KiB");
    var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(30);options.setNestingDepthLimit(30);
    try {
      var value=new Yaml(new SafeConstructor(options)).load(text);
      if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException();
      return normalize(map);
    }catch(Exception e){throw Problem.invalid("values","Values must be a YAML mapping without duplicate keys or unsupported types");}
  }
  private static Map<String,Object> normalize(Map<?,?> map) {
    Map<String,Object> result=new LinkedHashMap<>();
    for(var entry:map.entrySet()) {
      if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException();
      result.put(key,normalizeValue(entry.getValue()));
    }
    return result;
  }
  private static Object normalizeValue(Object v){
    if(v==null || v instanceof String || v instanceof Boolean || v instanceof Number)return v;
    if(v instanceof Map<?,?> m)return normalize(m);
    if(v instanceof List<?> l)return l.stream().map(HelmValues::normalizeValue).toList();
    throw new IllegalArgumentException();
  }
  public static Map<String,Object> diff(Map<String,Object> before,Map<String,Object> after) {
    Map<String,Object> changes=new TreeMap<>();Set<String> keys=new TreeSet<>(before.keySet());keys.addAll(after.keySet());
    for(String key:keys)if(!Objects.equals(before.get(key),after.get(key)) || before.containsKey(key)!=after.containsKey(key)){
      Map<String,Object> change=new LinkedHashMap<>();change.put("before",before.containsKey(key)?before.get(key):"(absent)");
      change.put("after",after.containsKey(key)?after.get(key):"(deleted)");changes.put(key,change);
    }
    return changes;
  }
  @SuppressWarnings("unchecked") public static Map<String,Object> merge(Map<String,Object> base,Map<String,Object> overlay){
    var result=new LinkedHashMap<>(base);
    overlay.forEach((key,value)->result.put(key,value instanceof Map<?,?> map && result.get(key) instanceof Map<?,?> old
        ?merge((Map<String,Object>)old,(Map<String,Object>)map):value));return result;
  }
}
