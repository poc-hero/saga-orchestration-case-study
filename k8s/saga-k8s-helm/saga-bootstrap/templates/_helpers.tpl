{{- define "saga-bootstrap.dockerconfigjson" -}}
{{- $auth := .Values.dockerhub.auth -}}
{{- if not $auth -}}
{{- $auth = printf "%s:%s" .Values.dockerhub.username .Values.dockerhub.password | b64enc -}}
{{- end -}}
{{- $authData := dict "auth" $auth -}}
{{- if and .Values.dockerhub.username .Values.dockerhub.password -}}
{{- $_ := set $authData "username" .Values.dockerhub.username -}}
{{- $_ := set $authData "password" .Values.dockerhub.password -}}
{{- end -}}
{{- $auths := dict .Values.dockerhub.server $authData -}}
{{- /* Docker Hub : plusieurs hosts selon le runtime (containerd, cri-o) */ -}}
{{- $_ := set $auths "https://registry-1.docker.io" $authData -}}
{{- $_ := set $auths "docker.io" $authData -}}
{{- $_ := set $auths "index.docker.io" $authData -}}
{{- dict "auths" $auths | toJson -}}
{{- end -}}
