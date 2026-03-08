# Helm – Aide-mémoire

Commandes Helm pour le projet Saga (`k8s/saga-k8s-helm`). Namespace par défaut : `saga-helm-dev`.

---

## Prérequis

```bash
# Mettre à jour les dépendances (library chart saga-service-lib)
helm dependency update k8s/saga-k8s-helm/site-service
helm dependency update k8s/saga-k8s-helm/uaa-service
```

---

## Bootstrap (obligatoire — une fois par namespace)

`saga-bootstrap` crée la ServiceAccount partagée `saga-app` utilisée par site-service et uaa-service. À installer **en premier**.

```bash
# Dev
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-dev --create-namespace

# Prod
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-prod --create-namespace

# Mettre à jour (ex. imagePullSecrets)
helm upgrade saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap -n saga-helm-dev

# Désinstaller (après les services)
helm uninstall saga-bootstrap -n saga-helm-dev
```

**Images privées** : soit via Helm (saga-bootstrap crée le Secret) :
```bash
# Option A : auth en base64 (echo -n "user:PAT" | base64)
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-dev --create-namespace \
  --set dockerhub.enabled=true \
  --set dockerhub.auth=andr7w \
  --set dockerhub.username=andr7w \
  --set dockerhub.password=<PAT>

# Option B : username + password
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-dev --create-namespace \
  --set dockerhub.enabled=true \
  --set dockerhub.username=<user> \
  --set dockerhub.password=<PAT>
```
> **Docker Hub** : PAT recommandé (Account Settings → Security). `dockerhub.auth` évite les problèmes de caractères spéciaux.

Soit manuellement :
```bash
kubectl create secret docker-registry dockerhub-creds \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=<user> --docker-password=<token> \
  -n saga-helm-dev
```

---

## Install / Upgrade / Uninstall

```bash
# 1. Bootstrap (voir section ci-dessus)
# 2. Installer uaa-service puis site-service (dev)
helm install uaa-service ./k8s/saga-k8s-helm/uaa-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/uaa-service/values-dev.yaml

helm install site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml

# Mettre à jour
helm upgrade site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml
  
helm upgrade uaa-service ./k8s/saga-k8s-helm/uaa-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/uaa-service/values-dev.yaml

# Désinstaller (ordre inverse : services puis bootstrap)
helm uninstall site-service -n saga-helm-dev
helm uninstall uaa-service -n saga-helm-dev
helm uninstall saga-bootstrap -n saga-helm-dev
```

---

## Releases (historique et état)

```bash
# Lister les releases d'un namespace
helm list -n saga-helm-dev

# Lister toutes les releases (tous namespaces)
helm list -A

# Historique des révisions (rollback)
helm history site-service -n saga-helm-dev

# Détails d'une release
helm status site-service -n saga-helm-dev
```

---

## Rollback

```bash
# Revenir à la révision précédente
helm rollback site-service 1 -n saga-helm-dev

# Revenir à une révision précise
helm rollback site-service 3 -n saga-helm-dev
```

---

## Valeurs et templates

```bash
# Afficher le manifeste généré (sans déployer)
helm template site-service ./k8s/saga-k8s-helm/site-service \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml

# Surcharger une valeur à l'install/upgrade
helm upgrade site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml \
  --set image.tag=1.2.0 \
  --set replicaCount=2

# Voir les valeurs résolues (merge values.yaml + values-dev.yaml)
helm get values site-service -n saga-helm-dev

# Dry-run (simuler sans appliquer)
helm upgrade site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-dev \
  -f ./k8s/saga-k8s-helm/site-service/values-dev.yaml \
  --dry-run --debug
```

---

## Dépendances

```bash
# Mettre à jour les charts dépendants (saga-service-lib)
helm dependency update k8s/saga-k8s-helm/site-service

# Lister les dépendances
helm dependency list k8s/saga-k8s-helm/site-service

# Rebuilder charts/ après modification du library
helm dependency build k8s/saga-k8s-helm/site-service
```

---

## Recherche et repo

```bash
# Rechercher un chart (Artifact Hub)
helm search hub nginx

# Repos locaux
helm repo list

# Installer depuis un repo
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install my-nginx bitnami/nginx
```

---

## Prod (exemple)

```bash
# 1. Bootstrap
helm install saga-bootstrap ./k8s/saga-k8s-helm/saga-bootstrap \
  -n saga-helm-prod --create-namespace

# 2. Services
helm install uaa-service ./k8s/saga-k8s-helm/uaa-service \
  -n saga-helm-prod \
  -f ./k8s/saga-k8s-helm/uaa-service/values-prod.yaml

helm install site-service ./k8s/saga-k8s-helm/site-service \
  -n saga-helm-prod \
  -f ./k8s/saga-k8s-helm/site-service/values-prod.yaml
```

---

## Récap rapide

| Action | Commande |
|--------|----------|
| Bootstrap | `helm install saga-bootstrap ./saga-bootstrap -n <ns> --create-namespace` |
| Installer | `helm install <release> <chart> -n <ns> -f values.yaml` |
| Mettre à jour | `helm upgrade <release> <chart> -n <ns> -f values.yaml` |
| Lister | `helm list -n <ns>` |
| Historique | `helm history <release> -n <ns>` |
| Rollback | `helm rollback <release> <revision> -n <ns>` |
| Désinstaller | `helm uninstall <release> -n <ns>` |
| Dry-run | `helm template <release> <chart> -f values.yaml` |
