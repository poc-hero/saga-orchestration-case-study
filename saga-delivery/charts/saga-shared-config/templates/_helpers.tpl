{{- define "saga-shared-config.fullname" -}}
{{- printf "%s-shared-config" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "saga-shared-config.labels" -}}
app.kubernetes.io/name: saga-shared-config
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
