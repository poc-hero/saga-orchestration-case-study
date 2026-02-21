# Déploiement sur cluster k3s via SCP

Guide pour déployer les manifests Kubernetes sur un cluster **k3s** (1 master + 2 workers), en copiant les manifests via SCP puis en exécutant `kubectl` sur le nœud master.

---

## Architecture cible

| Rôle   | Description                          |
|--------|--------------------------------------|
| Master | 1 VM – API server, scheduler, etcd  |
| Worker | 2 VMs – exécution des pods          |

Les commandes `kubectl` s'exécutent depuis le **nœud master**.

---

## Prérequis

- Cluster k3s opérationnel (1 master + 2 workers)
- Accès SSH au nœud master depuis ta machine de dev (Mac)

Sur k3s, `kubectl` est fourni par défaut :
- via `k3s kubectl`, ou
- via `kubectl` si le kubeconfig est configuré (`/etc/rancher/k3s/k3s.yaml`)

---

## Installation k3s (si besoin)

### Sur le master

```bash
curl -sfL https://get.k3s.io | sh -
```

Récupérer le token pour joindre les workers :
```bash
sudo cat /var/lib/rancher/k3s/server/node-token
```

### Sur chaque worker

```bash
curl -sfL https://get.k3s.io | K3S_URL=https://<IP_MASTER>:6443 K3S_TOKEN=<TOKEN> sh -
```

Remplace `<IP_MASTER>` par l'IP du master et `<TOKEN>` par la valeur obtenue ci-dessus.

### Vérifier le cluster

Sur le master :
```bash
sudo k3s kubectl get nodes
```

---

## Étape 1 : Copier le dossier k8s sur le master

Depuis ta machine de développement (Mac) :

```bash
scp -r /Users/mac/IdeaProjects/saga-orchestration-case-study/k8s user@<IP_MASTER>:~/
```

**Exemple :**
```bash
scp -r /Users/mac/IdeaProjects/saga-orchestration-case-study/k8s ubuntu@192.168.1.100:~/
```

---

## Étape 2 : Connexion au master et déploiement

```bash
ssh user@<IP_MASTER>
```

Sur le master :

```bash
# Si tu utilises k3s kubectl directement
sudo k3s kubectl apply -k ~/k8s/saga-k8s/overlays/dev/

# Ou avec kubectl classique (si KUBECONFIG est configuré)
kubectl apply -k ~/k8s/saga-k8s/overlays/dev/
```

**Production :**
```bash
sudo k3s kubectl apply -k ~/k8s/saga-k8s/overlays/prod/
```

---

## Configuration kubectl (optionnel)

Pour utiliser `kubectl` sans `sudo` :

```bash
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config
chmod 600 ~/.kube/config
echo 'export KUBECONFIG=~/.kube/config' >> ~/.bashrc
source ~/.bashrc
```

Ensuite : `kubectl apply -k ...` fonctionnera normalement.

---

## Commandes kubectl

Voir le fichier [KUBECTL_CHEATSHEET.md](./KUBECTL_CHEATSHEET.md) pour la liste complète (pods, logs, deployments, services, debug, etc.).

---

## Ingress avec k3s

k3s embarque **Traefik** par défaut. Les manifests actuels utilisent des annotations **nginx**.

Deux options :

1. **Installer nginx-ingress** (recommandé pour garder les manifests tels quels) :
   ```bash
   kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.2/deploy/static/provider/baremetal/deploy.yaml
   ```

2. **Désactiver Traefik** et adapter les manifests pour nginx-ingress, ou garder Traefik et modifier les annotations des Ingress.

---

## Mise à jour des manifests

À chaque modification :

1. **Sur ton Mac** : copier les manifests mis à jour
2. **Sur le master** : relancer `apply`

```bash
# Mac
scp -r /Users/mac/IdeaProjects/saga-orchestration-case-study/k8s user@<IP_MASTER>:~/

# Master (après ssh)
kubectl apply -k ~/k8s/saga-k8s/overlays/dev/
```

---

## Rappel

Les images Docker (site-service, uaa-service) sont tirées depuis le registry. Le cluster k3s les télécharge automatiquement au déploiement.
