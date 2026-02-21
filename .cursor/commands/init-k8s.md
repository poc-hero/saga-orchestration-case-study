---
description: Initialisation du projet pour déploiement K8s natif
agent: build
model: composer
---

# Init K8s — Déploiement Kubernetes natif (manifests YAML + Kustomize)

Crée un nouveau projet de déploiement Kubernetes avec des **manifests YAML bruts** organisés via **Kustomize**. Un jeu de templates de base est écrit une seule fois ; chaque service est déclaré via un **Kustomize component** qui le configure.

---

## Paramètres

Trois façons de fournir les paramètres :

### 1. Fichier de config via @

`/init-k8s @config.json`

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

`/init-k8s {"base":"my-k8s","services":["api-service","auth-service","svc3"]}`

### 3. Texte libre (ordre : projet puis services)

`/init-k8s saga-k8s site-service uaa-service indexation-service`

---

**Priorité** : fichier @ > JSON inline > texte libre. Valeurs par défaut si rien n'est fourni : `base = saga-k8s`, `services = [site-service, uaa-service]`.

---

## Principe : templates de base + un dossier par service

Les templates de Deployment, Service et Ingress sont écrits **une seule fois** dans `components/base-service/`. Chaque service a son propre dossier qui utilise ce component et le configure (nom, image, env vars).

---

## Arborescence à générer

**Répertoire parent** : `k8s/` (fixe). Le projet est créé dans `k8s/{base}/`.

Exemple : `base = saga-k8s` → `k8s/saga-k8s/`

```text
k8s/
└── {base}/
    ├── README.md
    ├── components/
    │   └── base-service/              # Templates génériques (écrits UNE fois)
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── ingress.yaml
    │       └── kustomization.yaml
    ├── services/                     # Un dossier par service
    │   ├── {svc1}/
    │   │   └── kustomization.yaml
    │   ├── {svc2}/
    │   │   └── kustomization.yaml
    │   └── {svc3}/
    │       └── kustomization.yaml
    └── overlays/
        ├── dev/
        │   ├── namespace.yaml
        │   └── kustomization.yaml
        └── prod/
            ├── namespace.yaml
            └── kustomization.yaml
```

---

## Contenu des fichiers

### components/base-service/deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: base-service
  labels:
    app: base-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: base-service
  template:
    metadata:
      labels:
        app: base-service
    spec:
      containers:
        - name: app
          image: registry.example.com/default:latest
          ports:
            - containerPort: 8080
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
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
```

### components/base-service/service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: base-service
spec:
  type: ClusterIP
  selector:
    app: base-service
  ports:
    - port: 8080
      targetPort: 8080
      protocol: TCP
```

### components/base-service/ingress.yaml

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: base-service
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  rules:
    - host: base-service.example.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: base-service
                port:
                  number: 8080
```

### components/base-service/kustomization.yaml

```yaml
apiVersion: kustomize.config.k8s.io/v1alpha1
kind: Component
resources:
  - deployment.yaml
  - service.yaml
  - ingress.yaml
```

### services/{svc}/kustomization.yaml (pour chaque service)

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

components:
  - ../../components/base-service

# Rename all resources from base-service to {svc}
namePrefix: ""
nameSuffix: ""

patches:
  # Set the service name on all resources
  - target:
      name: base-service
    patch: |
      - op: replace
        path: /metadata/name
        value: {svc}

  # Set image
  - target:
      kind: Deployment
      name: base-service
    patch: |
      - op: replace
        path: /spec/template/spec/containers/0/image
        value: registry.example.com/{base}/{svc}:latest
      - op: replace
        path: /metadata/labels/app
        value: {svc}
      - op: replace
        path: /spec/selector/matchLabels/app
        value: {svc}
      - op: replace
        path: /spec/template/metadata/labels/app
        value: {svc}

  # Set Service selector
  - target:
      kind: Service
      name: base-service
    patch: |
      - op: replace
        path: /spec/selector/app
        value: {svc}

  # Set Ingress host and backend
  - target:
      kind: Ingress
      name: base-service
    patch: |
      - op: replace
        path: /spec/rules/0/host
        value: {svc}.{base}.local
      - op: replace
        path: /spec/rules/0/http/paths/0/backend/service/name
        value: {svc}

# Add env vars specific to this service here:
# patches:
#   - target:
#       kind: Deployment
#     patch: |
#       - op: add
#         path: /spec/template/spec/containers/0/env
#         value:
#           - name: SITE_SERVICES_UAA_URL
#             value: "http://uaa-service:8080"
```

### overlays/dev/namespace.yaml

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: {base}-dev
```

### overlays/dev/kustomization.yaml

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: {base}-dev

resources:
  - namespace.yaml
  - ../../services/{svc1}
  - ../../services/{svc2}
  - ../../services/{svc3}
  # ... un entry par service
```

### overlays/prod/namespace.yaml

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: {base}-prod
```

### overlays/prod/kustomization.yaml

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: {base}-prod

resources:
  - namespace.yaml
  - ../../services/{svc1}
  - ../../services/{svc2}
  - ../../services/{svc3}

# Override for prod (replicas, resources, etc.)
patches:
  - target:
      kind: Deployment
    patch: |
      - op: replace
        path: /spec/replicas
        value: 2
```

---

## Règles inter-services

Si un service dépend d'un autre (ex. site-service appelle uaa-service), ajoute un patch dans `services/{svc}/kustomization.yaml` du service appelant :

```yaml
patches:
  - target:
      kind: Deployment
    patch: |
      - op: add
        path: /spec/template/spec/containers/0/env
        value:
          - name: SITE_SERVICES_UAA_URL
            value: "http://uaa-service:8080"
```

L'utilisateur devra indiquer quelles dépendances existent entre services. Si aucune n'est précisée, génère les services de façon indépendante.

---

## README.md (à la racine du projet généré)

Génère un README avec :
- Le nom du projet et la liste des services
- L'arborescence
- L'explication du principe (un component de base, N services, overlays par env)
- Les commandes :
  ```bash
  # Depuis la racine du projet :
  kubectl kustomize k8s/{base}/overlays/dev/
  kubectl apply -k k8s/{base}/overlays/dev/

  # Vérifier
  kubectl get pods -n {base}-dev

  # Appliquer l'overlay prod
  kubectl apply -k k8s/{base}/overlays/prod/

  # Appliquer un seul service (hors overlay)
  kubectl apply -k k8s/{base}/services/{svc1}/
  ```
  Les commandes kubectl sont exécutées depuis la racine du projet.
- Comment ajouter un nouveau service : créer `services/nouveau-service/kustomization.yaml` (copier un existant, changer le nom et l'image), l'ajouter dans les overlays

---

## Exécution

1. Lis les paramètres (fichier @, JSON inline, ou texte libre)
2. Crée le répertoire `k8s/{base}/` dans le répertoire courant (crée `k8s/` s'il n'existe pas)
3. Génère `components/base-service/` (une seule fois, templates génériques)
4. Pour chaque service, génère `services/{svc}/kustomization.yaml` en substituant `{base}` et `{svc}`
5. Génère les overlays `dev/` et `prod/` en référençant tous les services
6. Génère le README
7. Affiche un résumé de ce qui a été créé et les prochaines étapes
