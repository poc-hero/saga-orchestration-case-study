# Guide d'integration Vault avec Helm

Objectif: integrer des secrets Vault dans `site-service` de maniere explicite pour les devs, avec deploiement Kubernetes via Helm.

---

## Contexte et besoin

Tu veux:

- garder une reference explicite au secret dans `application.yaml`
- pouvoir appeler un endpoint qui lit ce secret
- stocker la valeur dans Vault
- recharger la valeur apres changement (via redemarrage du pod)

---

## Decision rapide

### Option A : Vault Agent Injector (sidecar)

### Option B : Spring Cloud Vault (native app)

### Option C : ESO + Reloader (infra)

---

## Comparatif des options

### Option A : Vault Agent Injector (sidecar)

**Fonctionnement**
- Un conteneur sidecar (Vault Agent) est injecte a cote de l'application Spring.
- Il recupere les secrets et les ecrit dans un fichier local partage (ex: `/vault/secrets/config`).
- Aucun code Java specifique Vault requis.

**Gestion dynamique**
- L'agent renouvelle les leases.
- Pour recharger l'application: signal `SIGHUP` ou appel de l'endpoint `/refresh`.

**Avantages**
- Standard officiel HashiCorp.
- Separation claire entre application et gestion des secrets.

### Option B : Spring Cloud Vault (native app)

Cette option est la plus adaptee si tu veux un marquage explicite dans `application.yaml` du type:

```yaml
app:
  secrets:
    my-secret: ${my-secret}
```

Ici `my-secret` est resolu depuis Vault au demarrage de l'application.

**Gestion dynamique**
- Support natif complet.
- L'application gere directement rotation/refresh.
- Possibilite d'utiliser Transit Engine pour chiffrement/dechiffrement.

**Avantages**
- Controle total depuis le code Java.
- Ideal pour besoins cryptographiques avances ou secrets complexes.

### Option C : ESO + Reloader (infra)

Plus simple cote ops, mais l'application lit des variables/env K8s:

```yaml
app:
  secrets:
    my-secret: ${MY_SECRET_VALUE} # from Vault via K8s Secret/ESO
```

Ce n'est pas un acces direct Vault depuis Spring.

**Gestion dynamique**
- ESO synchronise Vault vers Secret Kubernetes.
- Reloader detecte les changements et declenche un rolling restart.

**Avantages**
- Portabilite maximale (app decouplee de Vault).
- Simplicite pour les equipes DevOps.

---

## Implémentation Option A : Vault Agent Injector (sidecar) — étapes détaillées

Tu as déjà un serveur Vault disponible. Voici le plan d'implémentation par phase.

---

### Phase 0 : Prérequis Vault

**Contexte infra**

- Projet Terraform Vault : `~/Documents/projet/infomaniak/infra-openstack/terraform/infra_platform`
- Auth `kubernetes` **pas encore activée** dans Vault

**Checklist**

- [ ] Serveur Vault accessible depuis le cluster Kubernetes (URL, TLS)
- [ ] Auth `kubernetes` activée dans Vault
- [ ] Vault configuré pour communiquer avec l'API Kubernetes du cluster (token reviewer, Kubernetes host)

**0.1 Activer l'auth Kubernetes dans Vault**

```bash
export VAULT_ADDR="https://<VAULT_URL>"

vault login  # ou token selon ton setup

vault auth enable kubernetes
```

**0.2 Récupérer les paramètres du cluster K8s**

Depuis une machine qui a accès au cluster (ou depuis un pod dans le cluster) :

| Paramètre | Comment obtenir |
|-----------|-----------------|
| `kubernetes_host` | URL API du cluster (ex: `https://<master>:6443`) |
| `kubernetes_ca_cert` | CA du cluster (ex: `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`) |
| `token_reviewer_jwt` | JWT de la SA utilisée par Vault pour valider les tokens K8s |
| `issuer` | Issuer OIDC du cluster (souvent `https://kubernetes.default.svc.cluster.local`) |

**0.3 Configurer l'auth Kubernetes**

Créer une ServiceAccount + ClusterRoleBinding dédiée pour que Vault puisse appeler l'API TokenReview du cluster :

```bash
# Créer la SA vault-auth (dans un namespace dédié ou kube-system)
kubectl create serviceaccount vault-auth -n kube-system

# Créer le Secret contenant le JWT
kubectl apply -f - <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: vault-auth
  namespace: kube-system
  annotations:
    kubernetes.io/service-account.name: vault-auth
type: kubernetes.io/service-account-token
EOF

# Récupérer le JWT
kubectl get secret vault-auth -n kube-system -o jsonpath='{.data.token}' | base64 -d
```

Puis dans Vault :

```bash
vault write auth/kubernetes/config \
  kubernetes_host="https://<K8S_API>:6443" \
  kubernetes_ca_cert=@/path/to/ca.crt \
  token_reviewer_jwt="<JWT_obtenu_ci-dessus>" \
  issuer="https://kubernetes.default.svc.cluster.local"
```

> **Note** : Si ton infra Vault est gérée par Terraform (`infra_platform`), tu peux aussi ajouter un module `vault_auth_backend` + `vault_kubernetes_auth_backend_role` pour déclarer l'auth et les rôles en IaC. La Phase 1 suivante peut alors être faite en Terraform ou en CLI selon ton choix.

---

### Phase 1 : Vault — secret, policy et rôle

**1.1 Créer le secret dans Vault**

```bash
vault kv put secret/data/app/saga/dev/site-service my-secret="ma-valeur-test"
```

**1.2 Créer une policy Vault**

Fichier `policy-site-service.hcl` :

```hcl
path "secret/data/app/saga/dev/site-service" {
  capabilities = ["read"]
}
```

```bash
vault policy write site-service-policy policy-site-service.hcl
```

**1.3 Créer le rôle Vault**

> Prérequis : Phase 0 terminée (auth `kubernetes` activée et configurée).

```bash
vault write auth/kubernetes/role/site-service-role \
  bound_service_account_names=app-sa, saga-app \
  bound_service_account_namespaces=saga-helm-dev \
  policies=site-service-policy \
  ttl=1h
```

---

### Phase 2 : Kubernetes — installer Vault Agent Injector

**2.1 Installer le Vault Helm chart (Agent Injector)**

```bash
# Créer le dossier et copier la config
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config
chmod 600 ~/.kube/config

# Utiliser cette config
export KUBECONFIG=~/.kube/config

# Vérifier
kubectl get nodes


helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update

helm install vault-agent-injector hashicorp/vault \
  --set "injector.externalVaultAddr=http://84.234.25.73:30200" \
  -n vault-agent-injector --create-namespace
```

Ou selon ta config Vault : adapter `externalVaultAddr`, TLS, etc.

**2.2 Vérifier l'installation**

```bash
kubectl get pods -n vault-agent-injector
```

---

### Phase 3 : Helm chart site-service — annotations d'injection

**Implémenté** dans `k8s/saga-k8s-helm`. Le library chart `saga-service-lib` gère l'injection de façon conditionnelle (`vault.enabled`). Seul `site-service` active Vault.

**3.1 Annoter le Deployment pour l'injection**

Ajouter dans le template Deployment de `site-service` (ou via values) :

```yaml
annotations:
  vault.hashicorp.com/agent-inject: "true"
  vault.hashicorp.com/role: "site-service-role"
  vault.hashicorp.com/agent-inject-secret-config: "secret/data/saga/dev/site-service"
  vault.hashicorp.com/agent-inject-template-config: |
    {{- with secret "secret/data/saga/dev/site-service" -}}
    my.secret={{ .Data.data.my-secret }}
    {{- end }}
```

**3.2 Format du fichier généré**

L'agent écrit dans `/vault/secrets/config` un fichier properties :

```properties
my.secret=ma-valeur-test
```

**3.3 Configurer Spring pour charger ce fichier**

Variable d'environnement dans le Deployment :

```yaml
env:
  - name: SPRING_CONFIG_ADDITIONAL_LOCATION
    value: "file:/vault/secrets/"
```

Spring charge alors `config` (ou `config.properties`) depuis `/vault/secrets/`.

---

### Phase 4 : Application Spring — endpoint et property

**Implémenté** dans `site-service`.

**4.1 Propriété dans `application.yml`**

```yaml
app:
  secrets:
    my-secret: ${my.secret:}  # fourni par fichier Vault Agent
```

Fichier : `site-service/src/main/resources/application.yml`

**4.2 Endpoint de test**

- `GET /api/site/secret-check`
- Lit `app.secrets.my-secret`, la logge en INFO (valeur complète en dev, masquée `ab***xy` en prod).
- Réponse JSON : `status`, `secretLength`, `masked` (pour vérification fonctionnelle).

Fichier : `site-service/src/main/java/com/pocmaster/site/SiteController.java`

**4.3 Aucune dépendance Vault dans le code**

Pas de `spring-cloud-starter-vault-config`. L'app lit uniquement une property Spring classique, alimentée par le fichier du sidecar.

---

### Phase 5 : Stratégie de refresh

**Option 1 — Redémarrage manuel du pod**

```bash
kubectl rollout restart deployment/site-service -n saga-helm-dev
```

**Option 2 — SIGHUP vers le sidecar**

L'agent peut être configuré pour envoyer SIGHUP à l'app après renouvellement. Nécessite une config agent avancée.

**Option 3 — Endpoint `/actuator/refresh`**

Si Spring Cloud Config refresh est activé, un appel à `/actuator/refresh` peut recharger les properties (selon config).

Pour ton besoin actuel, **Option 1 (rollout restart)** est la plus simple et fiable.

---

### Phase 6 : Validation

- [ ] Vault Agent Injector déployé et fonctionnel
- [ ] Pod `site-service` démarre avec le sidecar
- [ ] Fichier `/vault/secrets/config` présent dans le pod
- [ ] Endpoint `/api/site/secret-check` retourne / logge la valeur
- [ ] Après modification Vault + `kubectl rollout restart`, nouvelle valeur visible

---

### Ordre d'exécution recommandé

1. Phase 0–1 : Vault (secret, policy, auth, rôle)
2. Phase 2 : Installer Vault Agent Injector dans le cluster
3. Phase 3 : Ajouter les annotations au chart Helm `site-service`
4. Phase 4 : Endpoint + property dans l'app
5. Phase 5–6 : Tester le refresh et valider