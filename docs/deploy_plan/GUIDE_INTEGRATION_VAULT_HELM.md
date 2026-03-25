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
- Il recupere les secrets et les ecrit dans un fichier local partage (ex: `/vault/secrets/application.properties`).
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
# secret = mount, app/saga/dev/site-service = path → API: secret/data/app/saga/dev/site-service
vault kv put secret/app/saga/dev/site-service my-secret="ma-valeur-test"
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

**1.3 Rôle Vault**

Rôle utilisé : **`app-role`** (selon tes prérequis infra). Le rôle doit autoriser la policy donnant accès au chemin `secret/data/app/saga/<env>/site-service`.

Si le rôle `app-role` n'existe pas encore :

```bash
vault write auth/kubernetes/role/app-role \
  bound_service_account_names=saga-app \
  bound_service_account_namespaces=saga-dev,saga-prod \
  policies=site-service-policy \
  ttl=1h
```

**Namespaces et ServiceAccount (GitOps `saga-delivery`)**

- Les workloads **dev** et **prod** sont deployes dans **`saga-dev`** et **`saga-prod`** (voir `versions/saga-*.yaml` et `argocd/00-namespaces.yaml`), plus **`saga-helm-dev`** si tu as encore un ancien deploiement.
- Le chart `saga-service-lib` **ne cree plus** de `ServiceAccount` ; les pods utilisent **`serviceAccount.name`** (ex. `saga-app` dans `saga-delivery/services/*/values.yaml`). La SA **`saga-app`** est creee par **`saga-delivery/argocd/01-serviceaccount-saga-app.yaml`** dans `saga-dev` et `saga-prod` (identite stable partagee pour Vault).
- **Vault** : `bound_service_account_names=saga-app` et `bound_service_account_namespaces` couvrant les namespaces deployes (ex. `saga-dev,saga-prod`).

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

Le `secretPath` est **configuré par environnement** dans les values :

| Fichier | `vault.secretPath` |
|---------|--------------------|
| `values.yaml` | `secret/data/app/saga/dev/site-service` (défaut) |
| `values-dev.yaml` | `secret/data/app/saga/dev/site-service` |
| `values-staging.yaml` | `secret/data/app/saga/staging/site-service` |
| `values-prod.yaml` | `secret/data/app/saga/prod/site-service` |

Le template utilise `{{ .Values.vault.secretPath }}` et itère sur toutes les clés du secret (pas de mapping manuel).

**3.2 Format du fichier généré**

L'agent écrit dans `/vault/secrets/application.properties` un fichier properties avec **toutes les clés** du secret :

```properties
my-secret=ma-valeur-test
autre-cle=autre-valeur
```

**3.3 Configurer Spring pour charger ce fichier**

Variable d'environnement dans le Deployment :

```yaml
{{- if and .Values.vault (eq .Values.vault.enabled true) }}
env:
  - name: SPRING_CONFIG_ADDITIONAL_LOCATION
    value: "file:/vault/secrets/"
{{- end }}
```

**Explication du bloc Helm**

| Élément | Signification |
|---------|---------------|
| `{{- if ... }}` | Condition Helm : n'injecte le bloc que si la condition est vraie |
| `and .Values.vault ...` | Vérifie que `vault` existe dans les values (évite une erreur si absent) |
| `eq .Values.vault.enabled true` | Vault doit être explicitement activé |
| `{{- end }}` | Fin du bloc conditionnel |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | Variable Spring Boot : emplacements supplémentaires pour charger la config |
| `file:/vault/secrets/` | Dossier où l'Agent Vault écrit les fichiers (ex: `application.properties`) |

Résultat : Spring charge `/vault/secrets/application.properties` au démarrage (Spring Boot cherche `application.properties` dans le dossier indiqué), ce qui lui permet de résoudre `${my-secret}` et les autres propriétés injectées par Vault.

**3.4 Interroger le container pour voir les valeurs du secret**

Depuis votre machine, exécutez dans le pod pour inspecter les fichiers écrits par l'Agent Vault :

```bash
# Récupérer le nom du pod site-service (namespace GitOps actuel : saga-dev)
POD=$(kubectl get pods -n saga-dev -l app=site-service-dev -o jsonpath='{.items[0].metadata.name}')

# Lister les fichiers dans /vault/secrets/
kubectl exec -n saga-dev $POD -c app -- ls -la /vault/secrets/

# Lire le contenu du fichier application.properties (clés/valeurs injectées par Vault)
kubectl exec -n saga-dev $POD -c app -- cat /vault/secrets/application.properties
```

> **Labels** : avec le chart actuel, le label `app` vaut le **Release.Name** Helm (ex. `site-service-dev`). Adapter `-l app=...` si besoin (`kubectl get pods -n saga-dev --show-labels`).

Exemple de sortie :

```
my-secret=ma-valeur-test
autre-cle=autre-valeur
```

> **Attention** : le nom du conteneur principal est `app` ; le sidecar Vault Agent s'appelle `vault-agent`. Pour exécuter dans le sidecar : `-c vault-agent`.

Alternative via l'endpoint applicatif (sans exec) :

```bash
# Via port-forward ou ingress
curl http://<site-service-url>/api/site/secret-check
```

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
kubectl rollout restart deployment/site-service-dev -n saga-dev
```

**Option 2 — SIGHUP vers le sidecar**

L'agent peut être configuré pour envoyer SIGHUP à l'app après renouvellement. Nécessite une config agent avancée.

**Option 3 — Endpoint `/actuator/refresh`** (implémenté pour Option B)

- `@RefreshScope` sur `SiteController` : le bean est recréé au prochain accès après un refresh
- `management.endpoints.web.exposure.include: health,refresh` : endpoint exposé
- `POST /actuator/refresh` déclenche `ContextRefresher.refresh()` → les beans `@RefreshScope` rechargent leurs `@Value`

```bash
# Après modification du secret dans Vault
curl -X POST http://site-service.saga-k8s.local/actuator/refresh

# Puis vérifier
curl http://site-service.saga-k8s.local/api/site/secret-check
```

> **Option A (sidecar)** : le refresh ne recharge pas le fichier `/vault/secrets/application.properties`. Il faut redémarrer le pod.
> **Option B (Spring Cloud Vault)** : le refresh recharge les PropertySources Vault si `ContextRefresher` est appelé.

---

### Phase 6 : Validation

- [ ] Vault Agent Injector déployé et fonctionnel
- [ ] Pod `site-service` démarre avec le sidecar
- [ ] Fichier `/vault/secrets/application.properties` présent dans le pod
- [ ] Endpoint `/api/site/secret-check` retourne / logge la valeur
- [ ] Après modification Vault + `kubectl rollout restart`, nouvelle valeur visible

---

### Ordre d'exécution recommandé

1. Phase 0–1 : Vault (secret, policy, auth, rôle)
2. Phase 2 : Installer Vault Agent Injector dans le cluster
3. Phase 3 : Ajouter les annotations au chart Helm `site-service`
4. Phase 4 : Endpoint + property dans l'app
5. Phase 5–6 : Tester le refresh et valider

---
---

## Implémentation Option B : Spring Cloud Vault (native app) — étapes détaillées

L'application Spring Boot se connecte **directement** à Vault au démarrage via la librairie `spring-cloud-starter-vault-config`. Pas de sidecar, pas d'annotation Kubernetes : la résolution des secrets est gérée nativement par Spring.

---

### Comparaison rapide avec Option A

| Critère | Option A (sidecar) | Option B (Spring Cloud Vault) |
|---------|-------------------|-------------------------------|
| Conteneurs par pod | 2 (app + vault-agent) | 1 (app seule) |
| Dépendance Java Vault | Aucune | `spring-cloud-starter-vault-config` |
| Refresh à chaud | Non (rollout restart) | Oui (`/actuator/refresh`) |
| Transit Engine | Non | Oui |
| Secrets dynamiques (DB, etc.) | Non | Oui |
| Portabilité hors K8s | Non | Oui (VM, bare metal, local) |
| Complexité infra | Injector + webhook + annotations | Config dans `application.yml` |
| Complexité app | Aucune | Dépendance + config Spring |

---

### Phase 0 : Prérequis Vault

Identiques à l'Option A :
- Vault accessible depuis le cluster (ou depuis la machine en local)
- Auth `kubernetes` activée (si K8s) — voir [Phase 0 Option A](#phase-0--prérequis-vault)
- Secret, policy et rôle créés — voir [Phase 1 Option A](#phase-1--vault--secret-policy-et-rôle)

> **Différence** : l'Option B peut aussi utiliser d'autres méthodes d'auth (AppRole, Token, etc.), pas uniquement Kubernetes.

---

### Phase 1 : Dépendances Maven

**1.1 Ajouter le BOM Spring Cloud dans le POM parent**

Fichier : `pom.xml` (racine)

```xml
<properties>
    <java.version>21</java.version>
    <spring-cloud.version>2023.0.4</spring-cloud.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**1.2 Ajouter le starter dans `site-service/pom.xml`**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-vault-config</artifactId>
</dependency>
```

> La version est gérée par le BOM. Pas besoin de la spécifier.

---

### Phase 2 : Configuration `application.yml`

Fichier : `site-service/src/main/resources/application.yml`

**2.1 Import Vault via ConfigData API (Spring Boot 3.x)**

```yaml
spring:
  application:
    name: site-service
  config:
    import: optional:vault://

  cloud:
    vault:
      uri: ${VAULT_ADDR:http://127.0.0.1:8200}
      authentication: KUBERNETES
      kubernetes:
        role: app-role
        service-account-token-file: /var/run/secrets/kubernetes.io/serviceaccount/token
      kv:
        enabled: true
        backend: secret
        default-context: app/saga/dev/site-service
        # profile-separator: /  # si tu veux des chemins par profil Spring

app:
  secrets:
    my-secret: ${my-secret:}
```

**Explication des clés :**

| Clé | Rôle |
|-----|------|
| `spring.config.import: optional:vault://` | Active l'import Vault via ConfigData. `optional:` = pas d'erreur si Vault est absent (utile en local) |
| `spring.cloud.vault.uri` | URL du serveur Vault. Variable d'env `VAULT_ADDR` par défaut |
| `spring.cloud.vault.authentication` | Méthode d'auth : `KUBERNETES`, `APPROLE`, `TOKEN` |
| `spring.cloud.vault.kubernetes.role` | Rôle Vault (même `app-role` que l'Option A) |
| `spring.cloud.vault.kubernetes.service-account-token-file` | Chemin du JWT du pod (monté automatiquement par K8s) |
| `spring.cloud.vault.kv.backend` | Mount point du KV engine (ici `secret`) |
| `spring.cloud.vault.kv.default-context` | Chemin du secret **sans le préfixe** `data/` (Spring Cloud Vault le gère) |
| `${my-secret:}` | Résolu depuis Vault au démarrage ; vide si absent |

**2.2 Profils par environnement**

Créer `application-dev.yml` et `application-prod.yml` pour surcharger le chemin Vault :

`application-dev.yml` :

```yaml
spring:
  cloud:
    vault:
      uri: http://84.234.25.73:30200
      kv:
        default-context: app/saga/dev/site-service
```

`application-prod.yml` :

```yaml
spring:
  cloud:
    vault:
      uri: https://vault.prod.example.com
      kv:
        default-context: app/saga/prod/site-service
```

---

### Phase 3 : Helm — variables d'environnement (pas d'annotations sidecar)

L'Option B ne nécessite **aucune annotation Vault** sur le Deployment. Il faut uniquement passer l'URL Vault et le profil Spring en variable d'environnement.

**3.1 Values Helm**

`values-dev.yaml` :

```yaml
env:
  SPRING_PROFILES_ACTIVE: "dev"
  VAULT_ADDR: "http://84.234.25.73:30200"
  SITE_SERVICES_UAA_URL: "http://uaa-service:8080"
  LOG_LEVEL: "DEBUG"

vault:
  enabled: false  # pas de sidecar — Spring Cloud Vault gère tout
```

`values-prod.yaml` :

```yaml
env:
  SPRING_PROFILES_ACTIVE: "prod"
  VAULT_ADDR: "https://vault.prod.example.com"
  SITE_SERVICES_UAA_URL: "http://uaa-service:8080"
  LOG_LEVEL: "INFO"

vault:
  enabled: false
```

**3.2 Résultat sur le Deployment**

Avec `vault.enabled: false`, le template library ne génère :
- **Aucune annotation** Vault (pas de sidecar injecté)
- **Aucune variable** `SPRING_CONFIG_ADDITIONAL_LOCATION`

L'app se connecte elle-même à Vault via `VAULT_ADDR`.

---

### Phase 4 : Endpoint `/api/site/secret-check`

Aucune modification du contrôleur. Le code existant fonctionne tel quel :

```java
@Value("${app.secrets.my-secret:}") String mySecret
```

Spring résout `${my-secret}` depuis la PropertySource Vault au démarrage, puis l'injecte dans `app.secrets.my-secret`.

---

### Phase 5 : Refresh à chaud (avantage de l'Option B)

**5.1 Activer le refresh**

Annoter les beans qui lisent les secrets avec `@RefreshScope` :

```java
@RestController
@RequestMapping("/api/site")
@RefreshScope
public class SiteController {
    @Value("${app.secrets.my-secret:}") String mySecret;
    // ...
}
```

**5.2 Déclencher le refresh**

Après modification du secret dans Vault :

```bash
# Modifier le secret
vault kv put secret/app/saga/dev/site-service my-secret="nouvelle-valeur"

# Recharger sans redémarrer le pod
curl -X POST http://site-service.saga-k8s.local/actuator/refresh
```

L'app relit les secrets depuis Vault et met à jour les `@Value`.

> **Prérequis** : exposer l'endpoint `/actuator/refresh` via `management.endpoints.web.exposure.include=refresh` dans `application.yml`.

---

### Phase 6 : Fonctionnalités avancées (Transit, secrets dynamiques)

**6.1 Transit Engine (chiffrement/déchiffrement)**

Spring Cloud Vault donne accès au Transit Engine pour chiffrer/déchiffrer des données sans exposer la clé :

```java
@Autowired
VaultOperations vaultOperations;

String ciphertext = vaultOperations.opsForTransit().encrypt("my-key", "données sensibles");
String plaintext  = vaultOperations.opsForTransit().decrypt("my-key", ciphertext);
```

> Nécessite la dépendance `spring-vault-core` (incluse dans le starter).

**6.2 Secrets dynamiques (ex. credentials DB temporaires)**

```yaml
spring:
  cloud:
    vault:
      database:
        enabled: true
        role: site-service-db
        backend: database
```

Vault génère des credentials DB temporaires, Spring les injecte dans `spring.datasource.username` et `spring.datasource.password`. Renouvellement automatique avant expiration du lease.

---

### Phase 7 : Tests locaux (sans Kubernetes)

Un des avantages de l'Option B : tout fonctionne en local.

**7.1 Avec un Vault local (Docker)**

```bash
docker run -d --name vault -p 8200:8200 \
  -e VAULT_DEV_ROOT_TOKEN_ID=root \
  hashicorp/vault:1.15

export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=root

vault kv put secret/app/saga/dev/site-service my-secret="valeur-locale"
```

`application.yml` utilise `optional:vault://` : si Vault est absent, l'app démarre avec les valeurs par défaut.

**7.2 Sans Vault (fallback)**

```bash
# Juste lancer l'app — ${my-secret:} se résout en chaîne vide
mvn spring-boot:run -pl site-service
```

---

### Phase 8 : Validation

- [ ] Dépendance `spring-cloud-starter-vault-config` ajoutée
- [ ] `spring.config.import: optional:vault://` dans `application.yml`
- [ ] Auth Kubernetes configurée (`role`, `service-account-token-file`)
- [ ] Pod démarre avec **1 seul conteneur** (pas de sidecar)
- [ ] Endpoint `/api/site/secret-check` retourne la valeur du secret
- [ ] Refresh à chaud : `POST /actuator/refresh` recharge le secret sans restart

---

### Ordre d'exécution recommandé (Option B)

1. Phase 0 : Vault (secret, policy, rôle) — réutiliser ceux de l'Option A
2. Phase 1 : Ajouter les dépendances Maven
3. Phase 2 : Configurer `application.yml` + profils
4. Phase 3 : Mettre à jour les values Helm (`vault.enabled: false`, `VAULT_ADDR`)
5. Phase 4 : Vérifier l'endpoint
6. Phase 5 : Tester le refresh à chaud
7. Phase 6 : (Optionnel) Transit, secrets dynamiques
8. Phase 7–8 : Tests locaux et validation

---

### Migration Option A → Option B

Si tu veux passer de A à B :

1. Ajouter les dépendances Maven (Phase 1)
2. Modifier `application.yml` (Phase 2)
3. Dans les values Helm : `vault.enabled: false` (supprime le sidecar)
4. Retirer `SPRING_CONFIG_ADDITIONAL_LOCATION` (automatique avec `vault.enabled: false`)
5. `helm upgrade` + vérifier que le pod a **1 conteneur**
6. Tester `/api/site/secret-check`

Le secret, la policy et le rôle Vault restent les mêmes.