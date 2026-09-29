{{- define "orchestrator.name" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "orchestrator.selector" -}}
app.kubernetes.io/name: ps-spoolers-perf-orchestrator
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
