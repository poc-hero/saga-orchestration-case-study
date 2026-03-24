# Docker – Aide-mémoire

Build et push des images des services Saga vers Docker Hub. Compte par défaut : `andr7w`.

---

## Prérequis

```bash
docker login
# Utiliser ton username + PAT (Personal Access Token)
```

---

## Build et push

**Depuis la racine du projet** (`saga-orchestration-case-study`) :

```bash
# site-service
docker build -f site-service/Dockerfile -t andr7w/saga-orchestration-site-service:latest .
docker push andr7w/saga-orchestration-site-service:latest

# uaa-service
docker build -f uaa-service/Dockerfile -t andr7w/saga-orchestration-uaa-service:latest .
docker push andr7w/saga-orchestration-uaa-service:latest
```

---

## Avec une version (tag)

```bash
VERSION=1.0.0

docker build -f site-service/Dockerfile -t andr7w/saga-orchestration-site-service:${VERSION} .
docker push andr7w/saga-orchestration-site-service:${VERSION}

docker build -f uaa-service/Dockerfile -t andr7w/saga-orchestration-uaa-service:${VERSION} .
docker push andr7w/saga-orchestration-uaa-service:${VERSION}
```

---

## Vérifier les images

```bash
docker images | grep saga-orchestration
```

---

## Récap

| Service        | Image                                        |
|----------------|----------------------------------------------|
| site-service   | `andr7w/saga-orchestration-site-service`     |
| uaa-service    | `andr7w/saga-orchestration-uaa-service`     |

**Commande générique** : depuis la racine du projet :

```bash
docker build -f <service>/Dockerfile -t andr7w/saga-orchestration-<service>:<tag> .
docker push andr7w/saga-orchestration-<service>:<tag>
```
