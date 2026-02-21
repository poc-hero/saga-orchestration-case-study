# saga-k8s — Déploiement Kubernetes natif

Manifests K8s pour **site-service** et **uaa-service** (projet saga-orchestration-case-study).

## Services

| Service | Rôle | Dépendance |
|---------|------|------------|
| **site-service** | Saga (validation, création, indexation) | Appelle uaa-service en HTTP |
| **uaa-service** | ACL (règles d'accès) | Aucune |

## Arborescence

```
saga-k8s/
├── components/
│   └── base-service/          # Templates génériques (Deployment, Service, Ingress)
├── services/
│   ├── site-service/          # Config site-service + SITE_SERVICES_UAA_URL
│   └── uaa-service/
└── overlays/
    ├── dev/                   # Namespace saga-k8s-dev, 1 replica
    └── prod/                  # Namespace saga-k8s-prod, 2 replicas
```

## Prérequis

- `kubectl` avec accès à un cluster K8s
- Images disponibles dans le registry : `registry.example.com/saga-k8s/site-service`, `registry.example.com/saga-k8s/uaa-service`

## Commandes

```bash
# Prévisualiser les manifests générés (dev)
kubectl kustomize overlays/dev/

# Appliquer l'overlay dev (tous les services)
kubectl apply -k overlays/dev/

# Vérifier
kubectl get pods -n saga-k8s-dev
kubectl get svc -n saga-k8s-dev
kubectl get ingress -n saga-k8s-dev

# Appliquer l'overlay prod
kubectl apply -k overlays/prod/

# Appliquer un seul service (hors overlay)
kubectl apply -k services/site-service/
```

## Configuration site-service

Le service **site-service** appelle **uaa-service** via l'URL définie dans la variable d'environnement `SITE_SERVICES_UAA_URL`. En K8s, cette URL pointe vers le Service interne : `http://uaa-service:8080` (même namespace).

## Ajouter un nouveau service

1. Créer `services/nouveau-service/kustomization.yaml` (copier `services/uaa-service/` et adapter)
2. Ajouter dans `overlays/dev/kustomization.yaml` et `overlays/prod/kustomization.yaml` :
   ```yaml
   resources:
     - ../../services/nouveau-service
   ```
