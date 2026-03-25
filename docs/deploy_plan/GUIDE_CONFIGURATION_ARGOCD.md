# Guide de configuration Argo CD — Saga

## Clusters impliqués

| Nom logique | IP (exemple) | Rôle |
|-------------|--------------|------|
| **cluster-argocd** | `84.234.25.73` | Héberge l'instance Argo CD (namespace `argocd`), les `Application`, `ApplicationSet`, secrets repo et cluster |
| **cluster-workload** | `84.234.26.236` | Héberge les microservices (`site-service`, `uaa-service`, namespaces `saga-dev` / `saga-prod`) |

> **Règle** : chaque commande ci-dessous indique **sur quel cluster** elle doit être exécutée. Ne pas confondre.

---

## Phase 1 — Installer Argo CD

> **Cluster : `cluster-argocd` (`84.234.25.73`)**

```bash
kubectl create namespace argocd

kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

Attendre que les pods soient prêts :

```bash
kubectl -n argocd get pods -w
```

### Accès UI / API

Exposer le serveur Argo CD (NodePort, Ingress ou port-forward selon ton infra) :

```bash
# exemple NodePort rapide
kubectl -n argocd patch svc argocd-server -p '{"spec": {"type": "NodePort"}}'
kubectl -n argocd get svc argocd-server
```

Récupérer le mot de passe admin initial :

```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

---

## Phase 2 — Enregistrer le dépôt Git

> **Cluster : `cluster-argocd` (`84.234.25.73`)**

Le secret qui donne à Argo CD l'accès au repo Git.

Fichier template : `argocd/bootstrap/repository-secret.yaml.template`

```bash
export GIT_TOKEN="ghp_xxxxxxxxxxxxxx"   # ton Personal Access Token GitHub

envsubst < argocd/bootstrap/repository-secret.yaml.template | kubectl apply -f -
```

Vérification :

```bash
kubectl -n argocd get secrets -l argocd.argoproj.io/secret-type=repository
```

---

## Phase 3 — Enregistrer le cluster workloads dans Argo CD

C'est **la pièce manquante** si Argo CD ne sait pas où déployer. L'enregistrement se fait en deux temps : préparer les credentials **sur le cluster workloads**, puis créer le secret **sur le cluster Argo CD**.

### 3.1 Créer un ServiceAccount pour Argo CD

> **Cluster : `cluster-workload` (`84.234.26.236`)**

Ce ServiceAccount donne à Argo CD le droit de déployer sur ce cluster.

```bash
kubectl create namespace argocd-manager 2>/dev/null || true

kubectl create serviceaccount argocd-manager -n argocd-manager

kubectl create clusterrolebinding argocd-manager-binding \
  --clusterrole=cluster-admin \
  --serviceaccount=argocd-manager:argocd-manager
```

### 3.2 Créer un token longue durée (K8s 1.24+)

> **Cluster : `cluster-workload` (`84.234.26.236`)**

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: argocd-manager-token
  namespace: argocd-manager
  annotations:
    kubernetes.io/service-account.name: argocd-manager
type: kubernetes.io/service-account-token
EOF
```

### 3.3 Récupérer le token et la CA

> **Cluster : `cluster-workload` (`84.234.26.236`)**

```bash
export WORKLOAD_BEARER_TOKEN=$(kubectl -n argocd-manager get secret argocd-manager-token \
  -o jsonpath='{.data.token}' | base64 -d)

export WORKLOAD_CA_DATA=$(kubectl -n argocd-manager get secret argocd-manager-token \
  -o jsonpath='{.data.ca\.crt}')
```

Vérifier que les variables ne sont pas vides :

```bash
echo "TOKEN length: ${#WORKLOAD_BEARER_TOKEN}"
echo "CA length: ${#WORKLOAD_CA_DATA}"
```

### 3.4 Appliquer le secret cluster dans Argo CD

> **Cluster : `cluster-argocd` (`84.234.25.73`)** — exécuté **via Ansible depuis ton poste** (pas manuellement sur le cluster)

Fichier template : `argocd/bootstrap/cluster-secret-saga-workload-dev.yaml.template`

**Méthode Ansible (recommandée)** — depuis le repo infra Ansible :

```bash
cd /Users/mac/Documents/projet/infomaniak/infra-openstack/ansible

ansible-playbook -i envs/platform/00_inventory.yml argocd-register-workload.yml \
  -e saga_gitops_repo_root=/Users/mac/IdeaProjects/saga-orchestration-case-study \
  -e workload_bearer_token="$WORKLOAD_BEARER_TOKEN" \
  -e workload_ca_data="$WORKLOAD_CA_DATA"
```

Ce que fait le playbook :
1. Copie le template `cluster-secret-saga-workload-dev.yaml.template` vers le master Argo CD (`84.234.25.73`)
2. Exécute `envsubst` + `kubectl apply` **sur ce master** avec les variables passées en `-e`
3. Nettoie le répertoire de staging

**Méthode manuelle** (alternative, directement sur le master Argo CD) :

```bash
envsubst < argocd/bootstrap/cluster-secret-saga-workload-dev.yaml.template | kubectl apply -f -
```

Ce secret contient :

| Champ | Valeur |
|-------|--------|
| `name` | `saga-workload-dev` (doit correspondre à `cluster:` dans `saga-delivery/versions/saga-dev.yaml`) |
| `server` | `https://84.234.26.236:6443` (API Kubernetes du cluster workloads) |
| `bearerToken` | Token du ServiceAccount `argocd-manager` créé à l'étape 3.2 |
| `caData` | CA du cluster workloads (base64) |

### 3.5 Vérification

> **Cluster : `cluster-argocd` (`84.234.25.73`)**

```bash
# via kubectl
kubectl -n argocd get secrets -l argocd.argoproj.io/secret-type=cluster

# via CLI argocd
argocd cluster list
```

Tu dois voir une entrée avec le nom `saga-workload-dev` et l'URL `https://84.234.26.236:6443`.

### 3.6 Port API

Si le port n'est pas 6443, vérifier sur le cluster workloads :

> **Cluster : `cluster-workload` (`84.234.26.236`)**

```bash
kubectl cluster-info
```

Et adapter le champ `server` dans le template.

---

## Phase 4 — Appliquer la Root Application

> **Cluster : `cluster-argocd` (`84.234.25.73`)**

La Root Application pointe vers `saga-delivery/argocd/` et charge automatiquement l'`ApplicationSet`, les namespaces et la ServiceAccount.

Fichier : `argocd/bootstrap/root-application.yaml`

```bash
kubectl apply -f argocd/bootstrap/root-application.yaml
```

Ce que la Root Application synchronise (dossier `saga-delivery/argocd/`) :

| Fichier | Cluster de destination | Rôle |
|---------|----------------------|------|
| `00-namespaces.yaml` | `cluster-argocd` (in-cluster, car `destination: https://kubernetes.default.svc`) | Crée `saga-dev` / `saga-prod` — **attention** : si ces namespaces doivent exister sur le cluster workloads, l'`ApplicationSet` les crée via `CreateNamespace=true` |
| `01-serviceaccount-saga-app.yaml` | `cluster-argocd` (même destination que la root) | ServiceAccount `saga-app` |
| `applicationset.yaml` | `cluster-argocd` | Génère les `Application` enfants |

Les **Applications enfants** (ex. `site-service-dev`) déploient vers **`cluster-workload`** (`saga-workload-dev`) grâce à `destination.name` lu depuis `versions/saga-dev.yaml`.

---

## Phase 5 — Vérification complète

### Sur `cluster-argocd` (`84.234.25.73`)

```bash
# ApplicationSet
kubectl -n argocd get applicationsets.argoproj.io

# Applications générées
kubectl -n argocd get applications.argoproj.io

# CLI
argocd app list
argocd app get site-service-dev
argocd app get uaa-service-dev
```

### Sur `cluster-workload` (`84.234.26.236`)

```bash
# Namespaces
kubectl get ns | grep saga

# Pods
kubectl -n saga-dev get pods

# ConfigMaps (vérifier que les profils sont montés)
kubectl -n saga-dev get configmaps

# ServiceAccount
kubectl -n saga-dev get sa saga-app
```

---

## Résumé du flux

```text
1. cluster-argocd : Argo CD installé, repo Git enregistré (Phase 1-2)
2. cluster-workload : ServiceAccount argocd-manager créé, token + CA récupérés (Phase 3.1-3.3)
3. cluster-argocd : secret cluster appliqué → Argo CD connaît saga-workload-dev (Phase 3.4)
4. cluster-argocd : root-application.yaml → charge applicationset.yaml (Phase 4)
5. cluster-argocd : ApplicationSet génère site-service-dev, uaa-service-dev, etc.
6. cluster-workload : Argo CD déploie les manifests Helm (Deployment, Service, ConfigMap, Ingress)
```

---

## Dépannage

| Symptôme | Cluster | Piste |
|----------|---------|-------|
| `argocd cluster list` ne montre pas `saga-workload-dev` | `cluster-argocd` | Le secret n'a pas été créé (Phase 3.4) ou le label `argocd.argoproj.io/secret-type: cluster` manque |
| Application en `Unknown` / cluster inconnu | `cluster-argocd` | Le champ `cluster:` dans `versions/saga-dev.yaml` ne correspond pas au `name` du secret |
| Timeout / connection refused vers `84.234.26.236` | réseau | Firewall : le pod `argocd-application-controller` sur `cluster-argocd` doit pouvoir joindre `https://84.234.26.236:6443` |
| TLS error | `cluster-argocd` | La `caData` dans le secret ne correspond pas au certificat du cluster workloads. Temporairement : `"insecure": true` dans le champ `config` du secret |
| Pods non créés sur `cluster-workload` | `cluster-workload` | Vérifier `argocd app get <app> --refresh` ; vérifier les droits du ServiceAccount `argocd-manager` |
| `403 FORBIDDEN` Vault login | `cluster-workload` | Vault `auth/kubernetes` doit être configuré pour l'API du **cluster-workload** (pas cluster-argocd). Voir `GUIDE_INTEGRATION_VAULT_HELM.md` |

---

## Fichiers de référence dans le dépôt

| Fichier | Rôle |
|---------|------|
| `argocd/bootstrap/repository-secret.yaml.template` | Secret repo Git |
| `argocd/bootstrap/cluster-secret-saga-workload-dev.yaml.template` | Secret cluster `saga-workload-dev` |
| `argocd/bootstrap/root-application.yaml` | Root Application |
| `saga-delivery/argocd/applicationset.yaml` | ApplicationSet |
| `saga-delivery/argocd/00-namespaces.yaml` | Namespaces `saga-dev` / `saga-prod` |
| `saga-delivery/argocd/01-serviceaccount-saga-app.yaml` | ServiceAccount `saga-app` |
| `saga-delivery/versions/saga-dev.yaml` | Config env dev (cluster, namespace, services) |
| `saga-delivery/versions/saga-prod.yaml` | Config env prod |
