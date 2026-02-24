# saga-k8s-helm — Déploiement Helm

Déploiement Kubernetes de **site-service** et **uaa-service** via Helm charts, sans outil GitOps requis.

> Cette approche est **indépendante** de `k8s/saga-k8s/` (Kustomize). Les deux peuvent coexister.

---

## Structure (approche DRY avec library chart)

```text
k8s/saga-k8s-helm/
├── saga-bootstrap/                      # Namespace + ServiceAccount partagée (saga-app)
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
│       └── serviceaccount.yaml
├── saga-service-lib/                   # Library chart — templates réutilisables (Helm 3)
│   ├── Chart.yaml                       # type: library — ne se déploie pas
│   └── templates/
│       ├── _helpers.tpl                 # fullname, labels, selectorLabels
│       ├── _deployment.yaml              # define "saga-service-lib.deployment"
│       ├── _service.yaml                 # define "saga-service-lib.service"
│       ├── _ingress.yaml                 # define "saga-service-lib.ingress"
│       ├── _configmap.yaml              # define "saga-service-lib.configmap"
│       └── _serviceaccount.yaml         # define "saga-service-lib.serviceaccount"
├── site-service/                        # Chart applicatif — dépend de saga-service-lib
│   ├── Chart.yaml                       # dependencies: saga-service-lib
│   ├── values.yaml                      # Valeurs spécifiques site-service
│   ├── values-dev.yaml
│   ├── values-prod.yaml
│   └── templates/                       # Fichiers légers qui include le library
│       ├── deployment.yaml             # {{ include "saga-service-lib.deployment" . }}
│       ├── service.yaml
│       ├── ingress.yaml
│       ├── configmap.yaml
│       └── serviceaccount.yaml
├── uaa-service/
│   └── (même structure — values différents)
└── README.md
```

**Principe DRY** : la logique commune (Deployment, Service, Ingress, ConfigMap) est définie dans `saga-service-lib`. La ServiceAccount `saga-app` est créée une fois par `saga-bootstrap` et partagée par les deux services (`serviceAccount.create: false`, `serviceAccount.name: saga-app`). Les `imagePullSecrets` viennent de la SA — **ne pas mettre `serviceAccount.create: true`** sinon le pod utiliserait une SA locale sans credentials.

---

## Prérequis

- **Helm 3** installé (`brew install helm` ou [docs officielles](https://helm.sh/docs/intro/install/))
- Accès à un cluster Kubernetes (`kubectl cluster-info`)
- Les images Docker disponibles dans le registry (ex. `andr7w/saga-orchestration-site-service`)

---

## Premier déploiement — mettre à jour les dépendances

Avant le premier `helm install`, téléchargez le library chart dans `charts/` :

```bash
helm dependency update k8s/saga-k8s-helm/site-service
helm dependency update k8s/saga-k8s-helm/uaa-service
```

Ou depuis chaque chart :

```bash
cd k8s/saga-k8s-helm/site-service && helm dependency update && cd -
cd k8s/saga-k8s-helm/uaa-service && helm dependency update && cd -
```

---

## Déploiement rapide (dev)

### 0. Bootstrap (obligatoire — une fois par namespace)

```bash
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-dev --create-namespace
```

### 1. Déployer uaa-service

```bash
helm install uaa-service ./k8s/saga-k8s-helm/uaa-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/uaa-service/values-dev.yaml
```

### 2. Déployer site-service

```bash
helm install site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml
```

### 3. Vérifier

```bash
kubectl get all -n saga-helm-dev
```

---

## Déploiement prod

```bash
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-prod --create-namespace

helm install uaa-service ./k8s/saga-k8s-helm/uaa-service \
  -n saga-helm-prod \
  -f ./k8s/saga-k8s-helm/uaa-service/values-prod.yaml

helm install site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-prod \
  -f ./k8s/saga-k8s-helm/site-service/values-prod.yaml
```

---

## Opérations courantes

### Mettre à jour un service (ex. nouvelle image)

```bash
helm upgrade site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml \
  --set image.tag=1.2.0
```

### Surcharger une valeur à la volée

```bash
helm upgrade site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  --set replicaCount=3 \
  --set env.LOG_LEVEL=TRACE
```

### Voir le manifeste généré (dry-run)

```bash
helm template site-service ./k8s/saga-k8s-helm/site-service \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml
```

### Rollback

```bash
helm history site-service -n saga-helm-dev
helm rollback site-service 1 -n saga-helm-dev
```

### Désinstaller

```bash
helm uninstall site-service -n saga-helm-dev
helm uninstall uaa-service -n saga-helm-dev
```

### Lister les releases

```bash
helm list -n saga-helm-dev
```

---

## Paramètres principaux (values.yaml)

| Paramètre | Description | Défaut |
|-----------|-------------|--------|
| `replicaCount` | Nombre de réplicas | `1` |
| `image.repository` | Image Docker | `andr7w/saga-orchestration-<service>` |
| `image.tag` | Tag de l'image | `latest` |
| `image.pullPolicy` | Pull policy | `IfNotPresent` |
| `service.type` | Type de Service K8s | `ClusterIP` |
| `service.port` | Port du Service | `8080` |
| `ingress.enabled` | Activer l'Ingress | `true` |
| `ingress.host` | Hostname Ingress | `<service>.saga-k8s.local` |
| `env.*` | Variables d'environnement injectées via ConfigMap | voir values.yaml |
| `resources.requests/limits` | CPU/mémoire | `100m-500m CPU, 256Mi-512Mi mem` |
| `probes.liveness/readiness` | Probes actuator | `/actuator/health` |
| `serviceAccount.create` | Créer un ServiceAccount | `true` |
| `serviceAccount.name` | Nom du ServiceAccount | `saga-app` |
| `imagePullSecrets` | Secrets pour pull d'images privées | `[]` |

---

## Valeurs par environnement

| Fichier | Différences par rapport aux défauts |
|---------|-------------------------------------|
| `values-dev.yaml` | `tag: latest`, `LOG_LEVEL: DEBUG`, 1 réplica |
| `values-prod.yaml` | `tag: 1.0.0`, `LOG_LEVEL: INFO`, 2 réplicas, resources augmentées |

---

## Modifier la logique commune

Pour changer le Deployment, Service, Ingress, etc. pour **tous** les services Saga :

1. Éditer les templates dans `saga-service-lib/templates/`
2. Mettre à jour les dépendances : `helm dependency update` dans site-service et uaa-service
3. Les deux charts héritent automatiquement des changements

---

## Différence avec saga-k8s (Kustomize)

| | saga-k8s (Kustomize) | saga-k8s-helm (Helm) |
|--|----------------------|----------------------|
| **Approche** | Component + patches JSON | Library chart + values |
| **DRY** | Component base-service partagé | saga-service-lib partagé |
| **Paramétrage** | overlays/dev, overlays/prod | values-dev.yaml, values-prod.yaml |
| **Déploiement** | `kubectl apply -k overlays/dev` | `helm install ... -f values-dev.yaml` |
| **Rollback** | `kubectl rollout undo` | `helm rollback` |

Les deux approches sont indépendantes et déploient les mêmes ressources K8s.
