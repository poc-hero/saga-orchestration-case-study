# Plan de déploiement Kubernetes — Saga orchestration case study

Objectif : déployer **site-service** et **uaa-service** sur Kubernetes, avec livraison déclarative (Argo CD), gestion des secrets (Vault), et deux approches (manifests classiques + Helm). Ce document adapte le plan générique (Tontine) au projet **saga-orchestration-case-study**.

---

## Vue d’ensemble du projet Saga

| Composant | Rôle | Déployé |
|-----------|------|---------|
| **saga-transaction-lib** | Lib réactive (SagaExecutor, SagaStep, TransactionContext) | Non (incluse dans site-service) |
| **site-service** | Saga en 3 étapes (validation+création, ACL, indexation), port 9090 | Oui |
| **uaa-service** | ACL (règles d’accès), port 9091 | Oui |

**Dépendance** : site-service appelle uaa-service en HTTP. En K8s, l’URL UAA sera le Service interne (ex. `http://uaa-service.saga-<env>.svc.cluster.local:8080`).

---

## Projets proposés (adaptés)

| Projet | Rôle | Contenu principal |
|--------|------|-------------------|
| **saga-k8s** | Définitions Kubernetes | Manifests K8s (YAML), Helm charts pour **site-service** et **uaa-service** |
| **saga-delivery** | Livraison déclarative (GitOps) | Argo CD Applications / ApplicationSet, valeurs par env (image tag, config) |
| **saga-terraform** (optionnel) | Infra as Code | Cluster, Vault, DNS, registry |
| **saga-orchestration-case-study** (existant) | Application | Code source, Dockerfiles pour site-service et uaa-service, CI build d’images |

Flux cible :

```
[ site-service, uaa-service ]  →  CI build images  →  Registry
       ↑
[ saga-delivery ]  →  Argo CD  →  lit  [ saga-k8s ]  +  valeurs (image tag, env)
       ↑
[ saga-k8s ]  =  Helm charts  (site-service, uaa-service)  — Deployment, Service, Ingress
       ↑
Secrets  ←  Vault  (External Secrets Operator)
```

---

## Phase 0 : Prérequis (inchangés)

- Concepts K8s : Pod, Deployment, Service, ConfigMap, Secret, Namespace, Ingress, Helm, GitOps (Argo CD).
- Bonnes pratiques : une app par Pod, probes (liveness/readiness), limits/requests, non-root, secrets hors Git, un namespace par env (`saga-dev`, `saga-prod`).

---

## Phase 1 : Projet **saga-k8s** (base Kubernetes)

**Rôle** : décrire comment tournent **site-service** et **uaa-service** dans le cluster.

### 1.1 Structure de `saga-k8s` (Kustomize + Components)

Le dossier `k8s/saga-k8s` utilise **Kustomize** avec l'approche **Component** : un composant générique (`base-service`) est patchépar chaque service, puis composé dans les overlays par environnement.

```text
k8s/saga-k8s/
├── README.md
├── components/
│   └── base-service/                        # Kustomize Component — template réutilisable
│       ├── kustomization.yaml               # kind: Component (apiVersion v1alpha1)
│       ├── deployment.yaml                  # Deployment générique (name: base-service)
│       ├── service.yaml                     # Service ClusterIP générique (port 8080)
│       └── ingress.yaml                     # Ingress générique (nginx rewrite-target)
├── services/
│   ├── site-service/
│   │   └── kustomization.yaml              # Utilise le component, patches : image, labels,
│   │                                        #   envFrom (configMapRef site-service-config),
│   │                                        #   Service name, Ingress host
│   └── uaa-service/
│       └── kustomization.yaml              # Idem : image, labels, Service name, Ingress host
├── overlays/
│   ├── dev/
│   │   ├── kustomization.yaml              # namespace: saga-k8s-dev
│   │   │                                    #   resources: namespace, serviceaccount, services/*
│   │   │                                    #   configMapGenerator (SITE_SERVICES_UAA_URL, LOG_LEVEL)
│   │   │                                    #   secretGenerator (dockerhub-creds)
│   │   │                                    #   patches: serviceAccountName saga-app
│   │   ├── namespace.yaml                  # Namespace saga-k8s-dev
│   │   ├── serviceaccount-default.yaml     # ServiceAccount saga-app + imagePullSecrets
│   │   └── secrets/
│   │       └── dockerconfigjson.dev.json   # Docker registry credentials (dev)
│   └── prod/
│       ├── kustomization.yaml              # namespace: saga-k8s-prod
│       │                                    #   idem dev + patch replicas: 2, LOG_LEVEL=INFO
│       ├── namespace.yaml                  # Namespace saga-k8s-prod
│       ├── serviceaccount-default.yaml
│       └── secrets/
│           └── dockerconfigjson.prod.json  # Docker registry credentials (prod)
└──   vault/                                   # (optionnel) Vault policies
    ├── policy.hcl.example
    └── README.md
```

> **Helm** : les charts Helm sont dans un dossier **indépendant** `k8s/saga-k8s-helm/` (voir section 1.1b ci-dessous).

#### Fonctionnement Kustomize

| Couche | Rôle | Fichier clé |
|--------|------|-------------|
| **Component** (`components/base-service/`) | Template générique : Deployment (probes, resources, `JAVA_TOOL_OPTIONS`), Service ClusterIP :8080, Ingress nginx. | `kustomization.yaml` (kind: Component) |
| **Service** (`services/<name>/`) | Spécialise le component : image Docker, labels `app: <name>`, nom Service/Ingress, host Ingress, `envFrom` (site-service uniquement). | `kustomization.yaml` (patches JSON Patch) |
| **Overlay** (`overlays/<env>/`) | Compose les services dans un namespace (`saga-k8s-dev`/`saga-k8s-prod`), injecte ConfigMap (URL UAA, log level), Secret (docker creds), ServiceAccount. | `kustomization.yaml` (configMapGenerator, secretGenerator) |

Déploiement d'un environnement :

```bash
# Dev
kubectl apply -k k8s/saga-k8s/overlays/dev

# Prod
kubectl apply -k k8s/saga-k8s/overlays/prod
```

### 1.1b Structure de `saga-k8s-helm` (Helm charts — indépendant, approche DRY)

Le dossier `k8s/saga-k8s-helm/` contient les mêmes services sous forme de **Helm charts**, avec un **library chart** partagé pour éviter la duplication. Il est **indépendant** de `saga-k8s` (Kustomize) et ne nécessite **aucun outil GitOps** (Argo CD) — `helm install` suffit.

```text
k8s/saga-k8s-helm/
├── saga-service-lib/                        # Library chart (type: library) — templates réutilisables
│   ├── Chart.yaml                           # type: library — ne se déploie pas seul
│   └── templates/
│       ├── _helpers.tpl                      # fullname, labels, selectorLabels
│       ├── _deployment.yaml                  # define "saga-service-lib.deployment"
│       ├── _service.yaml                     # define "saga-service-lib.service"
│       ├── _ingress.yaml                     # define "saga-service-lib.ingress"
│       ├── _configmap.yaml                  # define "saga-service-lib.configmap"
│       └── _serviceaccount.yaml              # define "saga-service-lib.serviceaccount"
├── site-service/                            # Chart applicatif — dépend de saga-service-lib
│   ├── Chart.yaml                            # dependencies: saga-service-lib (file://../saga-service-lib)
│   ├── values.yaml                           # image, env (SITE_SERVICES_UAA_URL), ingress.host, etc.
│   ├── values-dev.yaml
│   ├── values-prod.yaml
│   └── templates/                            # Fichiers légers : {{ include "saga-service-lib.xxx" . }}
│       ├── deployment.yaml
│       ├── service.yaml
│       ├── ingress.yaml
│       ├── configmap.yaml
│       └── serviceaccount.yaml
└── uaa-service/
    └── (même structure — values spécifiques uaa-service)
```

**Principe DRY** : la logique commune (Deployment, Service, Ingress, ConfigMap, ServiceAccount) est définie une seule fois dans `saga-service-lib`. Les charts applicatifs ne fournissent que leurs valeurs. Avant le premier déploiement : `helm dependency update` dans chaque chart.

#### Déploiement Helm (sans Argo CD)

```bash
# Dev — déployer les deux services
helm install uaa-service ./k8s/saga-k8s-helm/uaa-service \
  -n saga-helm-dev --create-namespace \
  -f ./k8s/saga-k8s-helm/uaa-service/values-dev.yaml

helm install site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev --create-namespace \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml

# Mettre à jour (nouvelle image)
helm upgrade site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml \
  --set image.tag=1.2.0

# Rollback
helm rollback site-service 1 -n saga-helm-dev

# Désinstaller
helm uninstall site-service -n saga-helm-dev
```

#### Comparaison Kustomize vs Helm

| | saga-k8s (Kustomize) | saga-k8s-helm (Helm) |
|--|----------------------|----------------------|
| **Paramétrage** | Overlays + patches JSON | `values.yaml` + `--set` |
| **Déploiement** | `kubectl apply -k` | `helm install/upgrade` |
| **Rollback** | `kubectl rollout undo` | `helm rollback` (avec historique) |
| **Namespace** | Défini dans kustomization.yaml | Flag `-n` + `--create-namespace` |
| **Pré-requis** | kubectl seul | Helm 3 + kubectl |

### 1.2 Points spécifiques Saga

- **site-service** :
  - Variable d’environnement ou ConfigMap : **`site.services.uaa.url`** = URL du Service uaa-service (ex. `http://uaa-service:8080` en ClusterIP, ou `http://uaa-service.saga-{{ .Values.namespace }}.svc.cluster.local:8080`).
  - Port applicatif : 9090 en local, en K8s on peut garder 8080 (Spring Boot) pour simplifier les Services.
  - Probes : `/actuator/health` (liveness, readiness) si Spring Boot Actuator est présent.
- **uaa-service** :
  - Aucune dépendance vers site-service.
  - Port 8080 (ou 9091 si tu gardes la cohérence avec la démo locale).
- **Ordre de déploiement** : uaa-service peut être déployé avant ou en parallèle ; site-service doit pouvoir résoudre l’URL de uaa-service au runtime (pas de démarrage synchrone obligatoire).

### 1.3 Éléments communs aux deux Deployments

- Image : `registry.example.com/saga/site-service`, `registry.example.com/saga/uaa-service` ; tag fourni par **saga-delivery** (valeurs par env).
- Config : ConfigMap par service avec `application.yaml` (et surcharge `site.services.uaa.url` pour site-service).
- Probes, resources (requests/limits), stratégie de rollout.
- Secrets : via Vault + ESO (External Secrets), pas de secret en clair dans Git.

---

## Phase 2 : Projet **saga-delivery** (GitOps)

**Rôle** : définir **quels services** sont déployés, **avec quelles versions** et **dans quels environnements**.

### 2.1 Structure proposée (orientée services)

```text
saga-delivery/   (ou dossier apps/ dans saga-k8s)
  services/
    site-service/
      Chart.yaml
      values.yaml
      envs/
        dev/
          values.yaml           # image.tag, site.services.uaa.url, replicas
          configmap-files/
            application.yaml
        prod/
          values.yaml
          configmap-files/
            application.yaml
      templates/
        deployment.yaml
        service.yaml
        ingress.yaml
        configmap.yaml
    uaa-service/
      Chart.yaml
      values.yaml
      envs/
        dev/
          values.yaml
          configmap-files/
            application.yaml
        prod/
          ...
      templates/
        ...
  saga-dev-version.yaml         # versions + replicas pour dev
  saga-prod-version.yaml
  templates/
    saga-applicationset.yaml    # ApplicationSet Argo CD
```

### 2.2 Fichier de versions par env : `saga-<env>-version.yaml`

**Exemple : `saga-dev-version.yaml`**

```yaml
environment: dev

charts:
  - name: site-service
    chartPath: services/site-service
    appVersion: "1.0.0"
    replicas: 1

  - name: uaa-service
    chartPath: services/uaa-service
    appVersion: "1.0.0"
    replicas: 1
```

**Exemple : `saga-prod-version.yaml`**

```yaml
environment: prod

charts:
  - name: site-service
    chartPath: services/site-service
    appVersion: "1.2.3"
    replicas: 2

  - name: uaa-service
    chartPath: services/uaa-service
    appVersion: "1.2.3"
    replicas: 2
```

### 2.3 Config site-service (URL UAA)

Dans **site-service** pour chaque env, la config doit définir l’URL du service UAA **dans le cluster** :

- **Dev** : `site.services.uaa.url: http://uaa-service.saga-dev.svc.cluster.local:8080`
- **Prod** : idem avec namespace `saga-prod`.

Cela peut être dans `envs/<env>/configmap-files/application.yaml` ou dans `envs/<env>/values.yaml` (variable d’env `SITE_SERVICES_UAA_URL` ou équivalent).

### 2.4 ApplicationSet Argo CD (adapté Saga)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: saga
  namespace: argocd
spec:
  generators:
    - list:
        elements:
          - env: dev
            service: site-service
            chartPath: services/site-service
          - env: dev
            service: uaa-service
            chartPath: services/uaa-service
          # répéter pour staging/prod si besoin
  template:
    metadata:
      name: '{{service}}-{{env}}'
    spec:
      project: default
      source:
        repoURL: https://github.com/<org>/saga-k8s.git
        targetRevision: main
        path: '{{chartPath}}'
        helm:
          valueFiles:
            - values.yaml
            - envs/{{env}}/values.yaml
      destination:
        server: https://kubernetes.default.svc
        namespace: 'saga-{{env}}'
      syncPolicy:
        automated:
          prune: true
          selfHeal: true
        syncOptions:
          - CreateNamespace=true
```

---

## Phase 3 : Secrets (Vault + ESO)

- **Vault** : stocker les secrets par env (ex. `secret/data/saga/prod`) si besoin (DB, clés API, etc.). Pour une démo sans persistance, les secrets peuvent être vides ou limités.
- **ESO** : SecretStore (accès Vault) + ExternalSecret par service/env si nécessaire.
- Les charts dans **saga-k8s** prévoient un montage ou `envFrom` vers un Secret K8s généré par ESO (sans mettre les valeurs en clair dans Git).

---

## Phase 4 : Terraform (optionnel)

- Même principe que le plan générique : **saga-terraform** pour cluster, Vault, DNS, registry.
- Structure : `modules/`, `environments/dev|prod/`, backend remote pour le state.

---

## Phase 5 : CI et images

- **site-service** et **uaa-service** : un Dockerfile par service (multi-stage Maven + JRE), build et push vers le registry avec tag = version ou SHA du commit.
- **saga-transaction-lib** : incluse dans le build de site-service (dépendance Maven), pas d’image séparée.
- Dans **saga-delivery**, les fichiers `saga-<env>-version.yaml` fixent `appVersion` (image tag) par service et par env.

---

## Plan d’action step-by-step (Saga)

| Step | Action | Projet |
|------|--------|--------|
| 1 | Créer **saga-k8s** avec structure Kustomize (`components/`, `services/`, `overlays/`). | saga-k8s |
| 2 | Écrire le Component base-service (Deployment, Service, Ingress) et les patches par service. | saga-k8s |
| 3 | Ajouter probes, resources, ConfigMap (URL UAA, LOG_LEVEL pour site-service), overlays dev/prod. | saga-k8s |
| 4 | Créer **saga-k8s-helm** : Helm charts **site-service** et **uaa-service** + values par env. | saga-k8s-helm |
| 5 | S’assurer que **site-service** et **uaa-service** ont un Dockerfile et un build d’image (CI → registry). | saga-orchestration-case-study |
| 6 | Créer **saga-delivery** : **saga-dev-version.yaml**, **saga-prod-version.yaml**, ApplicationSet Argo CD. | saga-delivery |
| 7 | Installer Argo CD ; déployer l’ApplicationSet ; vérifier que site-service et uaa-service tournent dans `saga-dev`. | saga-delivery / cluster |
| 8 | Configurer **site.services.uaa.url** par env (Service K8s uaa-service dans le bon namespace). | saga-k8s / saga-delivery |
| 9 | (Optionnel) Vault + ESO ; ExternalSecret pour chaque service si besoin de secrets. | saga-k8s / cluster |
| 10 | (Optionnel) **saga-terraform** pour cluster, Vault, DNS. | saga-terraform |
| 11 | Documenter : comment changer l’image tag d’un service, rollback, runbook. | saga-delivery / docs |

---

## Récap des repos

| Repo | Contenu |
|------|---------|
| **saga-k8s** | Manifests K8s (Kustomize : components, services, overlays), modèles ESO. |
| **saga-k8s-helm** | Helm charts **site-service** et **uaa-service**, values par env (dev/prod). |
| **saga-delivery** | Fichiers de versions par env (**saga-&lt;env&gt;-version.yaml**), ApplicationSet Argo CD, config par service/env. |
| **saga-terraform** | (Optionnel) Cluster, Vault, DNS, state remote. |
| **saga-orchestration-case-study** | Code source (site-service, uaa-service, saga-transaction-lib), Dockerfiles, CI. |

---

## Bonnes pratiques (rappel)

- Image : tag immuable (version ou SHA), pas `latest` en prod.
- Secrets : jamais en clair dans Git ; Vault + ESO.
- Environnements : namespaces `saga-dev`, `saga-prod` ; une Application Argo CD par (service, env).
- Santé : probes sur site-service et uaa-service ; Ingress avec TLS.
- **site-service** : toujours configurer **site.services.uaa.url** vers le Service uaa-service du même env.

---

## Premier pas concret

1. **Kustomize** : déployer avec `kubectl apply -k k8s/saga-k8s/overlays/dev`.
2. **Helm** : déployer avec `helm install` depuis `k8s/saga-k8s-helm/` (voir section 1.1b).
3. Tester en local avec **minikube** ou **kind** : déployer uaa-service, puis site-service, appeler `http://<site-service>/api/site/create`.
4. (Optionnel) Ajouter **saga-delivery** avec ApplicationSet Argo CD pour automatiser la synchro.

---

## Commandes utiles (référence)

```bash
# Kubernetes
kubectl get pods -n saga-dev
kubectl logs -f deployment/site-service -n saga-dev
kubectl logs -f deployment/uaa-service -n saga-dev
kubectl rollout status deployment/site-service -n saga-dev
kubectl rollout undo deployment/site-service -n saga-dev

# Kustomize
kubectl apply -k k8s/saga-k8s/overlays/dev
kubectl apply -k k8s/saga-k8s/overlays/prod

# Helm (depuis saga-k8s-helm)
helm install site-service ./k8s/saga-k8s-helm/site-service -n saga-helm-dev -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml
helm upgrade site-service ./k8s/saga-k8s-helm/site-service -n saga-helm-dev --set image.tag=1.0.0
helm rollback site-service 1 -n saga-helm-dev

# Argo CD
argocd app sync site-service-dev
argocd app get site-service-dev
```

---

Ce plan est une adaptation directe du **KUBERNETES_DEPLOYMENT_PLAN.md** (Tontine) au projet **saga-orchestration-case-study** : deux services (site-service, uaa-service), une dépendance HTTP entre eux, et les mêmes principes GitOps, Helm et Vault.
