{{/*
  Context (.) = chart parent (site-service, uaa-service).
*/}}
{{- define "saga-service-lib.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "saga-service-lib.labels" -}}
app: {{ include "saga-service-lib.fullname" . }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "saga-service-lib.selectorLabels" -}}
app: {{ include "saga-service-lib.fullname" . }}
{{- end }}
