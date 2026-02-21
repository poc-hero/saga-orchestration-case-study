# Plan de déploiement Kubernetes (Infomaniak) — Tontine

Objectif : déployer le service **tontine-app** sur Kubernetes (Infomaniak), avec livraison déclarative (Argo CD), gestion des secrets (Vault), et deux approches de déploiement (manifests classiques + Helm). Ce document sert aussi de guide d’apprentissage Kubernetes de A à Z.

---

## Vue d’ensemble des projets proposés

| Projet | Rôle | Contenu principal |
|--------|------|-------------------|
| **tontine-k8s** | Définitions Kubernetes (base) | Manifests K8s (YAML), Helm charts, bases des déploiements |
| **tontine-delivery** | Livraison déclarative (GitOps) | Argo CD apps, valeurs par env (image tag, env, config) |
| **tontine-terraform** (optionnel mais recommandé) | Infra as Code | Cluster Infomaniak (si API), Vault, DNS, stockage |
| **tontine-app** (existant) | Application | Dockerfile, build d’image (CI) |

Flux cible :

```
[ tontine-app ]  →  CI build image  →  Registry (Infomaniak ou autre)
       ↑
[ tontine-delivery ]  →  Argo CD  →  lit  [ tontine-k8s ]  +  valeurs (image tag, env)
       ↑
[ tontine-k8s ]  =  Helm chart ou manifests K8s  (Deployment, Service, Ingress, etc.)
       ↑
Secrets  ←  Vault  (injectés via External Secrets Operator ou Vault Agent)
```

---

## Phase 0 : Prérequis et apprentissage (Kubernetes A–Z)

### 0.1 Concepts Kubernetes à maîtriser (ordre suggéré)

1. **Pod** : plus petite unité déployable (un ou plusieurs conteneurs).
2. **Deployment** : gestion des Pods (réplicas, rollout, rollback).
3. **Service** : exposition stable (ClusterIP, LoadBalancer, NodePort).
4. **ConfigMap / Secret** : configuration et données sensibles (en K8s natif).
5. **Namespace** : isolation logique (dev, staging, prod).
6. **Ingress** : routage HTTP(S) vers les Services.
7. **Helm** : packaging (charts) et paramétrage (values) pour K8s.
8. **GitOps (Argo CD)** : état désiré dans Git = état du cluster.

### 0.2 Bonnes pratiques générales

- **Une seule responsabilité par Pod** (un process principal par conteneur).
- **Liveness / Readiness probes** sur tous les déploiements applicatifs.
- **Limites ressources** (requests/limits CPU et mémoire).
- **Non-root** dans les conteneurs quand c’est possible.
- **Secrets** : ne jamais commiter en clair ; utiliser Vault + opérateur (ex. ESO).
- **Environnements** : séparer par namespace (ex. `tontine-dev`, `tontine-prod`).

---

## Phase 1 : Projet **tontine-k8s** (base Kubernetes)

**Rôle** : tout ce qui décrit “comment tourne l’app” dans le cluster (sans les valeurs spécifiques à un env).

### 1.1 Structure proposée pour `tontine-k8s`

Tontine évolue en **plusieurs services** (tontine-core, tontine-uaa, …). Chaque service a son propre chart (ou son dossier base) pour être déployé indépendamment avec sa version d’image.

```text
tontine-k8s/
├── README.md
├── base/                          # Approche "Classic Kubernetes" (manifests bruts)
│   ├── namespace.yaml
│   ├── tontine-core/              # (optionnel) manifests par service si pas Helm
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── ingress.yaml
│   ├── tontine-uaa/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── ingress.yaml
│   └── kustomization.yaml         # Optionnel : Kustomize pour overlays
├── overlays/                      # Si tu utilises Kustomize (dev / prod)
│   ├── dev/
│   │   └── kustomization.yaml
│   └── prod/
│       └── kustomization.yaml
├── helm/
│   ├── tontine-core/              # Helm chart "tontine-core"
│   │   ├── Chart.yaml
│   │   ├── values.yaml
│   │   └── templates/
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       ├── ingress.yaml
│   │       ├── configmap.yaml     # app config + commons (montés par env)
│   │       ├── _helpers.tpl
│   │       └── NOTES.txt
│   ├── tontine-uaa/               # Helm chart "tontine-uaa"
│   │   ├── Chart.yaml
│   │   ├── values.yaml
│   │   └── templates/
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       ├── ingress.yaml
│   │       ├── configmap.yaml
│   │       └── ...
│   └── ...                        # un chart par service (tontine-app legacy, etc.)
└── vault/                         # Références / doc pour Vault (pas les secrets)
    ├── policy.hcl.example
    └── README.md
```

### 1.2 Approche 1 : Classic Kubernetes (dossier `base/`)

- Fichiers YAML “plats” : Deployment, Service, Ingress, ConfigMap.
- **Avantage** : simple, pas d’outil supplémentaire, idéal pour apprendre.
- **Inconvénient** : duplication si plusieurs env (dev/prod) ; pas de paramétrage central (image tag, env) sans Kustomize ou autre.

Tu peux ajouter **Kustomize** (`overlays/dev`, `overlays/prod`) pour varier image tag, réplicas, ressources, sans dupliquer tout le Deployment.

### 1.3 Approche 2 : Helm (dossier `helm/tontine-app/`)

- **Chart** = modèle + `values.yaml` (version d’image, env, réplicas, ingress host, etc.).
- **Avantage** : un seul chart, plusieurs env via des fichiers `values-*.yaml` ; standard dans l’écosystème K8s.
- **Inconvénient** : courbe d’apprentissage Helm (templates, `.Values`).

Recommandation : **commencer par `base/` pour bien comprendre les ressources**, puis **migrer vers Helm** pour la flexibilité et l’intégration avec Argo CD.

### 1.4 Éléments à prévoir dans les manifests (Deployment)

- **Image** : par service, ex. `registry.infomaniak.com/<tenant>/tontine-core`, `.../tontine-uaa` ; tag fourni par **tontine-delivery** (values par env).
- **Config** : fichiers Spring (`application.yaml`, etc.) et commons montés depuis les ConfigMap **propres à chaque service** (`apps/services/<service>/envs/<env>/configmap-files/*`) et, si besoin, depuis le chart `commons` (`apps/services/commons/envs/<env>/configmap-files/*`).
- **spring.profiles.active** : variable d’env (ou dans application.yaml) pour activer les profils commons (ex. `featureFlags,kafka`).
- **Probes** : `livenessProbe` / `readinessProbe` sur l’endpoint santé (ex. `/actuator/health` si Spring Boot).
- **Ressources** : `resources.requests` et `resources.limits` (CPU, mémoire).
- **Variables d’environnement** : ConfigMap pour la config non sensible ; Vault (ESO) pour les secrets (voir Phase 3).

---

## Phase 2 : Projet **apps / delivery** (GitOps orienté services)

**Rôle** : décrire **quels services** (charts Helm) sont déployés, **avec quelles versions** et **dans quels environnements**, en restant **orienté “apps first”** (services) plutôt que “envs first”.

Tontine évolue en **plusieurs micro‑services** (tontine-core, tontine-uaa, …). On organise donc la delivery autour des **services**, chacun ayant :

- un **chart Helm dédié** (`services/<service>`),
- des **valeurs globales communes à tous les envs** (`values.yaml` du chart),
- des **overrides par env** (`envs/<env>/values.yaml` + `configmap-files`),
- un **ConfigMap propre au service** (et à l’env) généré à partir de ces fichiers.

Les **commons** (featureFlags, kafka, etc.) sont gérés dans un **chart à part** (`services/commons`) avec ses propres fichiers de config par env.

Un chart parent `apps` regroupe les services et expose un **ApplicationSet Argo CD global**, ainsi que les **fichiers de version par env** (`tontine-<env>-version.yaml`).

### 2.1 Structure proposée (apps first, par services)

L’arborescence Helm orientée services peut ressembler à ceci (dans le repo qui porte les charts, par ex. `tontine-k8s` ou un projet dédié `apps`) :

```text
apps/
  services/
    commons/                         # Chart pour la config commune par env
      envs/
        dev/
          configmap-files/
            application.yaml
            application-kafka.yaml
            application-featureFlags.yaml
            ...
          values.yaml                # ex. Environment: dev
        staging/
          configmap-files/
            ...
          values.yaml                # ex. Environment: staging
        # prod/ ...
      templates/
        configmap.yaml               # ConfigMap "commons"
                                     # lit envs/{{ .Values.Environment }}/configmap-files/*
      Chart.yaml                     # name: commons, apiVersion, description…
      values.yaml                    # valeurs globales éventuelles (communes à tous les envs)

    tontine-core/                    # Chart propre au service tontine-core
      envs/
        dev/
          configmap-files/
            application.yaml         # config Spring spécifique à tontine-core en dev
            ...
          values.yaml                # variables d'env pour dev :
                                     #   STAGE, SPRING_PROFILES_ACTIVE, CONFIG_FILE_DIRECTORY, etc.
        staging/
          configmap-files/
            application.yaml         # config Spring pour staging
            ...
          values.yaml
        # prod/ ...
      templates/
        configmap.yaml               # ConfigMap du service
                                     # metadata.name = nom du service (ex. tontine-core)
                                     # data chargées depuis envs/{{ .Values.Environment }}/configmap-files/*
        deployment.yaml              # utilise :
                                     #   - values.yaml global
                                     #   - + overrides envs/<env>/values.yaml
                                     #   - probes, resources, strategy, type de service, mounts…
      Chart.yaml                     # chart du service
      values.yaml                    # valeurs globales communes à tous les envs :
                                     #   livenessProbe, readinessProbe, startupProbe
                                     #   resources, deploymentStrategy
                                     #   service.type (ClusterIP), additionalMounts, additionalVolumes, etc.

    # autres services (tontine-uaa, …) avec la même structure

    templates/
      tontine-applicationset.yaml    # ApplicationSet Argo CD global :
                                     #   - génère 1 Application par (service, env)
                                     #   - s’appuie sur les fichiers de version par env

    Chart.yaml                       # chart parent "apps" (umbrella)
                                     # liste les sous‑charts : commons, tontine-core, tontine-uaa, …

    tontine-dev-version.yaml         # fichier de version pour l'env dev
    tontine-staging-version.yaml     # fichier de version pour l'env staging
    tontine-prod-version.yaml        # fichier de version pour l'env prod
```

> Remarque : tu peux placer ce dossier `apps/` dans `tontine-k8s` ou dans un repo dédié `apps` ; l’important est que la structure reste orientée **services** et que l’ApplicationSet y ait accès.

### 2.2 Fichier de versions par env : `tontine-<env>-version.yaml`

On garde le principe “**un fichier de versions par env**”, mais **adapté à la nouvelle structure** :

- Chaque fichier (`tontine-dev-version.yaml`, `tontine-staging-version.yaml`, …) décrit **quels charts** déployer pour cet env, avec **quelle version d’app** et **combien de replicas**.
- On utilise un tableau `charts:` plutôt que des clés au niveau racine.
- Pour l’entrée **commons**, tu as précisé que **`replicas` et `appVersion` ne sont pas nécessaires** : on n’y met donc que ce qui est utile.

**Exemple : `tontine-dev-version.yaml`**

```yaml
# apps/tontine-dev-version.yaml — config de TOUS les services pour l'env dev
environment: dev

charts:
  # Chart "commons" : pas d'appVersion ni de replicas nécessaires
  - name: commons
    chartPath: services/commons

  # Service tontine-core
  - name: tontine-core
    chartPath: services/tontine-core
    appVersion: "1.2.3"          # version applicative (image tag, via values.yaml du chart)
    replicas: 1

  # Service tontine-uaa
  - name: tontine-uaa
    chartPath: services/tontine-uaa
    appVersion: "2.0.1"
    replicas: 1
```

- **Changer la version d’un service** = modifier `appVersion` dans l’entrée correspondante, commit + push → Argo CD synchronise (l’ApplicationSet relit ce fichier).
- Si tu veux gérer **des ressources spécifiques par env** (CPU/mémoire), tu peux :
  - soit les laisser dans `services/<service>/values.yaml` (communes à tous les envs),
  - soit les surcharger dans `services/<service>/envs/<env>/values.yaml`,
  - soit ajouter des champs optionnels dans `tontine-<env>-version.yaml` (ex. `resources:`) si tu veux vraiment tout centraliser ici.

### 2.3 ConfigMap et fichiers de config (commons + par service)

Tu as corrigé un point important : les **ConfigMap doivent être propres à chaque app** (pas un seul gros ConfigMap par env pour tout le monde).

Avec la nouvelle organisation :

- Le chart `services/commons` :
  - gère les **fichiers communs par env** (featureFlags, kafka, etc.) dans `envs/<env>/configmap-files/`,
  - expose un `configmap.yaml` qui lit ces fichiers en fonction de `.Values.Environment`.

- Chaque chart de **service** (`services/tontine-core`, `services/tontine-uaa`, …) :
  - a son propre `configmap.yaml` dans `templates/`, avec un `metadata.name` spécifique (ex. `tontine-core-config`),
  - lit les fichiers `envs/<env>/configmap-files/*` de ce service,
  - reçoit les variables d’env (STAGE, SPRING_PROFILES_ACTIVE, CONFIG_FILE_DIRECTORY, …) depuis `envs/<env>/values.yaml`.

**Résultat :**

- Les **commons** sont partagés via un chart dédié (`commons`), monté dans les Pods des services qui en ont besoin.
- Chaque service a un **ConfigMap séparé**, avec ses fichiers Spring (`application.yaml`, etc.) et ses propres variables d’environnement.

### 2.4 Helm : valeurs globales vs overrides par env

- Dans chaque chart de service (`services/tontine-core`, etc.) :
  - `values.yaml` contient les **valeurs globales** communes à tous les envs :
    - probes (liveness, readiness, startup),
    - `resources` par défaut,
    - `deploymentStrategy`,
    - type de service (`ClusterIP`), `additionalMounts`, `additionalVolumes`, etc.
  - `envs/<env>/values.yaml` contient les **spécificités de cet env** :
    - `Environment` (dev, staging, prod),
    - variables d’env comme `STAGE`, `SPRING_PROFILES_ACTIVE`, `CONFIG_FILE_DIRECTORY`, etc.

- Les fichiers de config Spring par env du service (`application.yaml`, …) sont dans :
  - `services/<service>/envs/<env>/configmap-files/`,
  - et sont injectés dans le Pod via le `configmap.yaml` du service.

Ainsi, tu as :

- **Un chart par service**, avec un cœur commun (`values.yaml`) + des **overrides par env**,
- **Un chart commons** pour les fichiers partagés,
- Le tout contrôlé par les fichiers de versions (`tontine-<env>-version.yaml`).

### 2.5 ApplicationSet global Argo CD (apps/templates/tontine-applicationset.yaml)

L’ApplicationSet est **global à tous les services et à tous les envs**. Il peut :

- lire une **liste (service, env)** depuis :
  - soit les fichiers `tontine-<env>-version.yaml`,
  - soit une liste inline (pour commencer simplement),
- générer pour chaque (service, env) une **Application Argo CD** qui :
  - pointe sur le chart (`chartPath`),
  - applique les `values.yaml` globaux + `envs/<env>/values.yaml`.

Un exemple simplifié (avec un générateur de liste explicite) :

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: tontine
  namespace: argocd
spec:
  generators:
    - list:
        elements:
          - env: dev
            service: commons
            chartPath: services/commons
          - env: dev
            service: tontine-core
            chartPath: services/tontine-core
          - env: dev
            service: tontine-uaa
            chartPath: services/tontine-uaa
          # tu peux ajouter ici staging/prod, ou bien dériver ça des fichiers de version
  template:
    metadata:
      name: '{{service}}-{{env}}'
    spec:
      project: default
      source:
        repoURL: https://github.com/<org>/tontine-k8s.git
        targetRevision: main             # peut varier par env (dev=develop, prod=main, etc.)
        path: apps/{{chartPath}}
        helm:
          valueFiles:
            - values.yaml
            - envs/{{env}}/values.yaml
      destination:
        server: https://kubernetes.default.svc
        namespace: 'tontine-{{env}}'
      syncPolicy:
        automated:
          prune: true
          selfHeal: true
        syncOptions:
          - CreateNamespace=true
          - ServerSideApply=true
```

**Remarques :**

- `targetRevision` peut être **n’importe quelle branche** (main, develop, feature/xxx) ou un **tag** (ex. v1.2.3) → tu peux par exemple déployer dev depuis `develop` et prod depuis `main` ou un tag.
- Tu pourras remplacer le générateur `list` par un générateur plus dynamique (ex. basé sur les fichiers `tontine-<env>-version.yaml`) pour dériver automatiquement la liste `(service, env, chartPath, appVersion, replicas, …)`.

### 2.6 Récap du modèle factorisé (orienté services)

| Élément | Fichier / ressource | Rôle |
|--------|----------------------|------|
| **Charts par service** | `apps/services/<service>/Chart.yaml` + `values.yaml` + `envs/<env>/...` | Un chart Helm par micro‑service, avec valeurs globales + overrides par env et ConfigMap propres à chaque app. |
| **Config commons** | `apps/services/commons/...` | Chart Helm contenant les fichiers de config partagés par env (featureFlags, kafka, etc.). |
| **Versions à déployer** (tous les services) | `apps/tontine-<env>-version.yaml` | Un fichier par env ; `charts[]` liste des services (name, chartPath, appVersion, replicas, …). L’ApplicationSet lit ces infos. |
| **Application Argo CD** | `apps/templates/tontine-applicationset.yaml` | ApplicationSet global qui génère 1 Application par (service, env). Aucune duplication de fichier par env ou par service. |

Ainsi : **1 chart par service**, **1 chart commons**, **1 fichier de versions par env** et **1 ApplicationSet global** suffisent pour piloter le déploiement de tous les services sur tous les environnements.

## Phase 3 : Secrets avec Vault et accès Kubernetes

**Objectif** : aucun secret en clair dans Git. Vault = source de vérité ; Kubernetes récupère les secrets via un opérateur.

### 3.1 Où héberger Vault ?

- **Infomaniak** : vérifier si un service “Vault” ou “secrets” existe ; sinon, Vault sur un petit cluster ou VM.
- **HashiCorp Cloud (HCP Vault)** : managé, simple pour démarrer.
- **Vault self-hosted** sur un cluster K8s (Helm chart officiel) : possible mais plus lourd à opérer.

### 3.2 Intégration Kubernetes ↔ Vault (2 approches courantes)

| Approche | Outil | Principe | Complexité |
|----------|--------|----------|------------|
| **External Secrets Operator (ESO)** | ESO | Lit des secrets depuis Vault (ou AWS, GCP…) et crée des `Secret` K8s. Tu déclares `ExternalSecret` + `SecretStore` (config Vault). | Moyenne |
| **Vault Agent Injector** | Vault Helm | Injecte des fichiers ou env dans le Pod au démarrage (sidecar). | Moyenne |

**Recommandation pour apprendre et pour Infomaniak** : **External Secrets Operator** — plus “GitOps friendly” (tu déclares dans Git quels secrets récupérer, sans logique dans l’image).

### 3.3 Flux Vault + ESO (résumé)

1. **Vault** : tu crées un secret (ex. `secret/data/tontine/prod`) avec les clés (DB password, API keys, etc.).
2. **K8s** : tu déploies un **SecretStore** (adresse Vault, auth : AppRole ou Kubernetes auth).
3. **K8s** : tu déploies un **ExternalSecret** qui dit “prendre `secret/data/tontine/prod` et créer un Secret K8s `tontine-app-secrets`”.
4. Le **Deployment** de tontine-app utilise `envFrom.secretRef` (ou `volumes`) sur ce Secret K8s.

Les **politiques Vault** et **rôles** (Kubernetes auth ou AppRole) limitent quel namespace / quelle app peut lire quel path — bonnes pratiques d’accès.

### 3.4 Où définir la “connexion” K8s ↔ Vault ?

- **tontine-k8s** : templates Helm (ou manifests) pour **SecretStore** et **ExternalSecret** (paramétrés par env).
- **tontine-delivery** : valeurs par env (ex. path Vault `tontine/prod`) et éventuellement le déploiement de l’opérateur ESO (ou dans un repo “bootstrap” / Terraform).

---

## Phase 4 : Terraform (projet **tontine-terraform**) — optionnel mais recommandé

**Rôle** : créer et configurer l’infra (cluster, Vault, DNS, etc.) de façon reproductible.

### 4.1 Utilité par composant

- **Kubernetes (Infomaniak)** : si Infomaniak expose une API (type “Kubernetes as a Service”), un module Terraform peut créer le cluster et récupérer kubeconfig.
- **Vault** : créer le namespace/policy/role Vault pour ESO, ou configurer HCP Vault.
- **Registry** : configurer l’accès au registry d’images (Infomaniak) pour le cluster (imagePullSecrets si besoin).
- **DNS** : si Infomaniak ou un autre fournisseur gère le DNS, créer les enregistrements pour `tontine.dev.example.com`, `tontine.example.com`.

### 4.2 Structure proposée pour `tontine-terraform`

```text
tontine-terraform/
├── README.md
├── backend.tf                 # Remote state (S3, Infomaniak Object Storage, etc.)
├── providers.tf
├── variables.tf
├── outputs.tf
├── main.tf
├── modules/
│   ├── infomaniak-k8s/        # Cluster K8s (si API dispo)
│   ├── vault-config/         # Policies, roles pour ESO
│   └── dns/                  # Optionnel
└── environments/
    ├── dev/
    │   └── terraform.tfvars
    └── prod/
        └── terraform.tfvars
```

Tu peux commencer par un **Terraform minimal** (juste un backend + variables) et ajouter les modules au fur et à mesure (d’abord Vault, puis K8s si l’API Infomaniak le permet).

---

## Phase 5 : Infomaniak (Kubernetes et Registry)

- **Kubernetes** : consulter la doc Infomaniak (managed Kubernetes, création de cluster, kubeconfig). Si c’est managé, tu n’as pas forcément besoin de Terraform pour le cluster au début ; tu peux te connecter avec `kubectl` et laisser Argo CD gérer les déploiements.
- **Registry** : utiliser le registry Infomaniak pour pousser l’image `tontine-app`. La CI (GitHub Actions, GitLab CI, etc.) build et push avec le tag (version ou commit). Dans **tontine-delivery** tu fixes `image.tag` pour chaque déploiement.
- **Ingress / Load Balancer** : selon l’offre Infomaniak (Ingress controller, certificats TLS). Adapter les manifests **Ingress** dans **tontine-k8s** (host, TLS, annotations si besoin).

---

## Plan d’action step-by-step (résumé)

| Step | Action | Projet(s) |
|------|--------|-----------|
| **1** | Créer le repo **tontine-k8s** avec structure `base/` + `helm/` (un chart par service : tontine-core, tontine-uaa, …). | tontine-k8s |
| **2** | Écrire les manifests **base** (Namespace, Deployment, Service, Ingress) par service. | tontine-k8s |
| **3** | Ajouter liveness/readiness, resources, montage **application.yaml** + **commons** (ConfigMap), et placeholder pour secrets (ESO). | tontine-k8s |
| **4** | Créer les **Helm charts** par service + **chart umbrella tontine-env** (subcharts) ; paramétrage image, config ; profils via ConfigMap. | tontine-k8s |
| **5** | S’assurer que chaque service a un Dockerfile et un build d’image (CI → registry), avec sa propre version. | tontine-core, tontine-uaa, … |
| **6** | Créer **tontine-delivery** : **argocd/applicationSets/tontine-appset.yaml** (un seul fichier pour tous les envs) ou **argocd/apps/tontine-dev.yaml** (une Application par env), **envs/dev/tontine-dev-version.yaml** (tous les services), **envs/dev/tontine-dev-configmap.yaml** (profils + commons). | tontine-delivery |
| **7** | Installer Argo CD. Créer **une Application par env** (tontine-dev, tontine-staging, tontine-prod) déployant le chart **tontine-env** avec **tontine-&lt;env&gt;-version.yaml**. | tontine-delivery / cluster |
| **8** | Définir les **profils** (SPRING_PROFILES_ACTIVE) et le contenu des **commons** dans le **ConfigMap** de l'env (tontine-dev-config), pas dans le fichier de versions. | tontine-delivery |
| **9** | Définir **application.yaml** par service (envs/dev/tontine-core/application.yaml, etc.) ; les versions restent dans **tontine-dev-version.yaml**. | tontine-delivery |
| **10** | Choisir et installer **Vault**. Créer les secrets par env (ou par service). | Vault |
| **11** | Installer **External Secrets Operator**. Configurer SecretStore et ExternalSecret par env / service. | tontine-k8s / cluster |
| **12** | (Optionnel) Créer **tontine-terraform** : backend, variables, module Vault, module cluster Infomaniak. | tontine-terraform |
| **13** | Ajouter les envs **staging** et **prod** (tontine-staging-version.yaml, tontine-prod-version.yaml + ConfigMaps + Applications). | tontine-delivery |
| **14** | Documenter runbook (changer image tag par service, rollback, activation des commons, accès Vault/K8s). | tontine-delivery / docs |

---

## Récap des repos

| Repo | Quand le créer | Contenu |
|------|----------------|---------|
| **tontine-k8s** | En premier | Manifests K8s, **Helm charts** (tontine-core, tontine-uaa) + **chart umbrella tontine-env** (subcharts), modèles ESO. |
| **tontine-delivery** | Dès que tu veux utiliser Argo CD | **Une Application par env** ; **un fichier de versions par env** (**tontine-&lt;env&gt;-version.yaml**) ; **ConfigMap par env** (profils + commons) ; **application.yaml** par service dans envs/&lt;env&gt;/&lt;service&gt;/. |
| **tontine-terraform** | Dès que tu veux automatiser l’infra | Cluster (si API), Vault config, DNS, state remote. |

Tu peux démarrer **sans Terraform** (cluster et Vault créés à la main) et introduire Terraform ensuite pour rendre l’infra reproductible.

---

## Bonnes pratiques (rappel)

- **Image** : tag immuable (version ou SHA du commit), éviter `latest` en prod.
- **Secrets** : jamais dans Git ; Vault + ESO (ou équivalent).
- **Environnements** : namespaces dédiés + une Argo CD Application par env.
- **Santé** : probes sur tous les déploiements ; Ingress avec TLS.
- **Ressources** : requests/limits sur chaque Deployment.
- **Documentation** : README dans chaque repo (objectif, prérequis, comment déployer).

---

## Premier pas concret (après lecture du plan)

1. **Créer le repo `tontine-k8s`** avec la structure `base/` et `helm/` (un chart par service : tontine-core, tontine-uaa).
2. **Dans `base/`** (ou dans un chart) : écrire Deployment, Service pour un premier service (ex. tontine-uaa) avec image, probes, montage **application.yaml** et **commons** (ConfigMap).
3. **Tester en local** : `minikube` ou `kind` + `kubectl apply` pour valider le déploiement.
4. **Créer `tontine-delivery`** : `envs/dev/commons/` (featureFlags.yaml, kafka.yaml), `envs/dev/tontine-uaa/values.yaml` + `application.yaml`, puis une Application Argo CD par service.
5. **Activer les commons** : dans `envs/dev/tontine-uaa/values.yaml` (ou application.yaml) définir `spring.profiles.active=featureFlags,kafka` pour ce service en dev.

---

## Commandes utiles (référence)

```bash
# Kubernetes
kubectl get pods -n <namespace>
kubectl describe pod <pod> -n <namespace>
kubectl logs -f deployment/tontine-app -n <namespace>
kubectl rollout status deployment/tontine-app -n <namespace>
kubectl rollout undo deployment/tontine-app -n <namespace>

# Helm (depuis tontine-k8s)
helm install tontine-app ./helm/tontine-app -n tontine-dev -f values-dev.yaml
helm upgrade tontine-app ./helm/tontine-app -n tontine-dev -f values-dev.yaml --set image.tag=1.2.3

# Argo CD (CLI)
argocd app sync tontine-dev
argocd app get tontine-dev
```

---

## Références et doc

- [Kubernetes Docs](https://kubernetes.io/docs/)
- [Helm](https://helm.sh/docs/)
- [Argo CD](https://argo-cd.readthedocs.io/)
- [External Secrets Operator](https://external-secrets.io/)
- [HashiCorp Vault](https://developer.hashicorp.com/vault/docs)
- Infomaniak : doc Kubernetes / Registry / Object Storage selon ton offre

Si tu veux, on peut détailler la **Step 1** (fichiers réels dans **tontine-k8s** : Deployment, Service, Ingress pour tontine-app) ou un **Dockerfile** + **CI** pour builder et pousser l’image.
