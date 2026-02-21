---
description: Initialisation du projet pour déploiement K8s avec Helm
agent: build
model: claude opus
---

# Init K8s Helm — Déploiement Kubernetes avec Helm

Crée un nouveau projet de déploiement Kubernetes avec **un chart Helm générique** réutilisé pour chaque service Spring Boot, et un fichier `values` par service.

---

## Paramètres

Trois façons de fournir les paramètres :

### 1. Fichier de config via @

`/init-k8s-helm @config.json`

Le fichier JSON doit contenir au minimum :
```json
{
  "base": "saga-k8s",
  "services": ["site-service", "uaa-service"]
}
```

On peut ajouter autant de services qu'on veut :
```json
{
  "base": "my-project",
  "services": ["api-service", "auth-service", "notification-service", "gateway"]
}
```

### 2. JSON inline

`/init-k8s-helm {"base":"my-k8s","services":["api-service","auth-service","svc3"]}`

### 3. Texte libre (ordre : projet puis services)

`/init-k8s-helm saga-k8s site-service uaa-service indexation-service`

---

**Priorité** : fichier @ > JSON inline > texte libre. Valeurs par défaut si rien n'est fourni : `base = saga-k8s`, `services = [site-service, uaa-service]`.

---

## Principe : UN chart, N values

**Ne pas dupliquer le chart pour chaque service.** Un seul chart générique contient tous les templates. Chaque service a son propre fichier `values/<svc>.yaml` qui configure image, ports, env vars, etc.

```bash
# Installer un service = même chart + values spécifique
helm install site-service ./helm/service-chart -f helm/values/site-service.yaml -n saga-dev
helm install uaa-service  ./helm/service-chart -f helm/values/uaa-service.yaml  -n saga-dev
```

---

## Arborescence à générer

**Répertoire parent** : `k8s/` (fixe). Le projet Helm est créé dans `k8s/{base}-helm/`.

Exemple : `base = saga-k8s` → `k8s/saga-k8s-helm/`

```text
k8s/
└── {base}-helm/
    ├── README.md
    └── helm/
        ├── service-chart/               # Chart générique (UN SEUL)
        │   ├── Chart.yaml
        │   ├── values.yaml
        │   └── templates/
        │       ├── _helpers.tpl
        │       ├── deployment.yaml
        │       ├── service.yaml
        │       ├── ingress.yaml
        │       └── configmap.yaml
        └── values/                      # Un fichier par service
            ├── {svc1}.yaml
            ├── {svc2}.yaml
            └── {svc3}.yaml
```

---

## Contenu des fichiers

### helm/service-chart/Chart.yaml

```yaml
apiVersion: v2
name: service-chart
description: Generic Helm chart for Spring Boot microservices
type: application
version: 0.1.0
appVersion: "1.0.0"
```

### helm/service-chart/values.yaml (valeurs par défaut)

```yaml
# -- Service name (overridden per service)
serviceName: my-service

replicaCount: 1

image:
  repository: registry.example.com/default
  tag: "latest"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8080

ingress:
  enabled: true
  className: nginx
  host: ""
  path: /
  pathType: Prefix
  tls: []

probes:
  liveness:
    path: /actuator/health
    port: 8080
    initialDelaySeconds: 30
    periodSeconds: 10
  readiness:
    path: /actuator/health
    port: 8080
    initialDelaySeconds: 15
    periodSeconds: 5

resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi

# -- Environment variables (key: value)
env: {}

# -- Config files mounted in /config (filename: content)
configFiles: {}
```

### helm/service-chart/templates/_helpers.tpl

```gotpl
{{/*
Service name — uses serviceName from values
*/}}
{{- define "service-chart.name" -}}
{{ .Values.serviceName }}
{{- end }}

{{/*
Fullname — release-aware
*/}}
{{- define "service-chart.fullname" -}}
{{ .Values.serviceName }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "service-chart.labels" -}}
app.kubernetes.io/name: {{ include "service-chart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "service-chart.selectorLabels" -}}
app.kubernetes.io/name: {{ include "service-chart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
```

### helm/service-chart/templates/deployment.yaml

```gotpl
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "service-chart.fullname" . }}
  labels:
    {{- include "service-chart.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "service-chart.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "service-chart.selectorLabels" . | nindent 8 }}
    spec:
      containers:
        - name: {{ include "service-chart.name" . }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.service.port }}
          {{- if .Values.env }}
          env:
            {{- range $key, $value := .Values.env }}
            - name: {{ $key }}
              value: {{ $value | quote }}
            {{- end }}
          {{- end }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: {{ .Values.probes.liveness.port }}
            initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: {{ .Values.probes.readiness.port }}
            initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- if .Values.configFiles }}
          volumeMounts:
            - name: config
              mountPath: /config
              readOnly: true
          {{- end }}
      {{- if .Values.configFiles }}
      volumes:
        - name: config
          configMap:
            name: {{ include "service-chart.fullname" . }}-config
      {{- end }}
```

### helm/service-chart/templates/service.yaml

```gotpl
apiVersion: v1
kind: Service
metadata:
  name: {{ include "service-chart.fullname" . }}
  labels:
    {{- include "service-chart.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "service-chart.selectorLabels" . | nindent 4 }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.port }}
      protocol: TCP
```

### helm/service-chart/templates/ingress.yaml

```gotpl
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "service-chart.fullname" . }}
  labels:
    {{- include "service-chart.labels" . | nindent 4 }}
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: {{ .Values.ingress.className }}
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: {{ .Values.ingress.path }}
            pathType: {{ .Values.ingress.pathType }}
            backend:
              service:
                name: {{ include "service-chart.fullname" . }}
                port:
                  number: {{ .Values.service.port }}
  {{- if .Values.ingress.tls }}
  tls:
    {{- toYaml .Values.ingress.tls | nindent 4 }}
  {{- end }}
{{- end }}
```

### helm/service-chart/templates/configmap.yaml

```gotpl
{{- if .Values.configFiles }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "service-chart.fullname" . }}-config
  labels:
    {{- include "service-chart.labels" . | nindent 4 }}
data:
  {{- range $filename, $content := .Values.configFiles }}
  {{ $filename }}: |
    {{- $content | nindent 4 }}
  {{- end }}
{{- end }}
```

---

## Fichiers values par service

Pour chaque service dans la liste, générer `helm/values/{svc}.yaml` :

```yaml
serviceName: {svc}

image:
  repository: registry.example.com/{base}/{svc}
  tag: "latest"

ingress:
  host: {svc}.{base}.local

# Ajouter les variables d'env spécifiques au service ici.
# Ex. pour un service qui dépend d'un autre :
# env:
#   SITE_SERVICES_UAA_URL: "http://uaa-service:8080"

# Ex. pour un fichier de config monté :
# configFiles:
#   application.yaml: |
#     server:
#       port: 8080
#     site:
#       services:
#         uaa:
#           url: http://uaa-service:8080
```

---

## Règles inter-services

Si un service dépend d'un autre (ex. site-service appelle uaa-service), ajoute dans le fichier `values/{svc}.yaml` du service appelant :

```yaml
env:
  SITE_SERVICES_UAA_URL: "http://uaa-service:8080"
```

L'utilisateur devra indiquer quelles dépendances existent entre services. Si aucune n'est précisée, génère les services de façon indépendante.

---

## README.md (à la racine du projet généré)

Génère un README avec :
- Le nom du projet et la liste des services
- L'arborescence
- L'explication du principe (un chart, N values)
- Les commandes :
  ```bash
  # Depuis k8s/{base}-helm/ :
  helm install {svc1} ./helm/service-chart -f helm/values/{svc1}.yaml -n {base}-dev --create-namespace
  helm install {svc2} ./helm/service-chart -f helm/values/{svc2}.yaml -n {base}-dev
  helm install {svc3} ./helm/service-chart -f helm/values/{svc3}.yaml -n {base}-dev

  # Vérifier
  kubectl get pods -n {base}-dev

  # Mettre à jour un service
  helm upgrade {svc1} ./helm/service-chart -f helm/values/{svc1}.yaml -n {base}-dev --set image.tag=1.2.3

  # Désinstaller un service
  helm uninstall {svc1} -n {base}-dev
  ```
  Les commandes helm sont exécutées depuis `k8s/{base}-helm/`.
- Comment ajouter un nouveau service : créer un fichier `helm/values/nouveau-service.yaml` et lancer `helm install`

---

## Exécution

1. Lis les paramètres (fichier @, JSON inline, ou texte libre)
2. Crée le répertoire `k8s/{base}-helm/` dans le répertoire courant (crée `k8s/` s'il n'existe pas)
3. Génère le chart générique `helm/service-chart/` (une seule fois)
4. Pour chaque service, génère `helm/values/{svc}.yaml` en substituant `{base}` et `{svc}`
5. Génère le README
6. Affiche un résumé de ce qui a été créé et les prochaines étapes
