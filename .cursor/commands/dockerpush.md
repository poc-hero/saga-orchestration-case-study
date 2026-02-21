---
description: Build et push d'une image Docker pour un service Spring Boot
agent: build
model: composer
---

# Docker Push — Build et push d'une image Docker

Build le Dockerfile d'un service et push l'image vers Docker Hub.

---

## Paramètres

### Texte libre (ordre : service, version, compte)

`/dockerpush site-service 1.0.0 andr7w`

### JSON inline

`/dockerpush {"service":"site-service","version":"1.0.0","account":"andr7w"}`

### Fichier de config via @

`/dockerpush @config.json`

```json
{
  "service": "site-service",
  "version": "1.0.0",
  "account": "andr7w"
}
```

---

**Valeurs par défaut** :
- `version` : `latest` si non fourni
- `account` : `andr7w` si non fourni
- `service` : obligatoire

---

## Ce qu'il faut exécuter

Pour un service `{svc}`, version `{version}`, compte `{account}` :

```bash
# 1. Build l'image depuis la racine du projet
docker build -f {svc}/Dockerfile -t {account}/{svc}:{version} .

# 2. Push vers Docker Hub
docker push {account}/{svc}:{version}
```

---

## Prérequis

- Le fichier `{svc}/Dockerfile` doit exister. Si ce n'est pas le cas, utiliser `/dockerfile {svc}` pour le générer.
- L'utilisateur doit être connecté à Docker Hub (`docker login`). Si la commande `docker push` échoue avec une erreur d'authentification, indiquer d'exécuter `docker login` d'abord.

---

## Exécution

1. Lis les paramètres (texte libre, JSON inline, ou fichier @)
2. Vérifie que `{svc}/Dockerfile` existe
3. Exécute `docker build -f {svc}/Dockerfile -t {account}/{svc}:{version} .`
4. Si le build réussit, exécute `docker push {account}/{svc}:{version}`
5. Affiche un résumé : image taguée, URL Docker Hub (`https://hub.docker.com/r/{account}/{svc}`)
