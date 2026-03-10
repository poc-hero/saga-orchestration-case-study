# Guide d'intégration MongoDB avec site-service

Objectif : ajouter un accès MongoDB au `site-service` pour valider la connectivité à une base MongoDB déployée sur le cluster Kubernetes, en mode sample.

---

## Contexte et périmètre

- **Collection** : `metadata` (attributs : `_id` géré par MongoDB, `editor`, `app_version`, `manager`, `description`)
- **Endpoints** :
  - `GET /api/site/metadata` — affiche tout le contenu de la collection
  - `GET /api/site/metadata/{id}` — affiche un document par son `_id`
  - `POST /api/site/metadata` — crée un nouveau document
- **Configuration** : tout via Vault (Spring Cloud Vault) ; aucune valeur par défaut pour l’environnement dev
- **Profil** : `application-mongo.yml` (profil réutilisable pour les services futurs nécessitant MongoDB)
- **Déploiement** : modifications uniquement dans les Helm values existants (pas de manifests K8s natifs)

---

## Plan d’implémentation

### 1. Application Java (site-service)

| Étape | Fichier / Action | Description |
|-------|------------------|-------------|
| 1.1 | `pom.xml` | Ajouter `spring-boot-starter-data-mongodb-reactive` |
| 1.2 | `application-mongo.yml` | Propriétés MongoDB (uri depuis Vault uniquement) ; profil à importer |
| 1.3 | `application-dev.yml` | Importer le profil `mongo` |
| 1.4 | Document `Metadata` | `@Id String id` (ObjectId Mongo), `editor`, `app_version`, `manager`, `description` |
| 1.5 | Repository `MetadataRepository` | Interface `ReactiveMongoRepository<Metadata, String>` |
| 1.6 | Controller | `GET /api/site/metadata`, `GET /api/site/metadata/{id}`, `POST /api/site/metadata` |

**Document Metadata** : `_id` géré par MongoDB (`@Id` sur un `String` ; MongoDB génère un ObjectId).

---

### 2. Profil mongo (application-mongo.yml)

Créer un profil `mongo` dans `application-mongo.yml` afin de pouvoir réutiliser la configuration MongoDB dans d’autres services sans dupliquer le code.

**Emplacement proposé** : `site-service/src/main/resources/application-mongo.yml` (ou module common partagé si créé plus tard).

```yaml
# application-mongo.yml — profil activé via spring.profiles.include: mongo
spring:
  data:
    mongodb:
      uri: ${mongodb-uri}   # Obligatoire, fourni par Vault — pas de valeur par défaut
```

Le profil est activé via `spring.profiles.include: mongo` dans les environnements qui en ont besoin. Les services sans MongoDB ne l’incluent pas.

---

### 3. Vault — stockage des credentials MongoDB

Toutes les configurations MongoDB sont stockées dans Vault. Aucune valeur par défaut dans les configs applicatives pour dev/prod.

**Option retenue** : Spring Cloud Vault. Les configs Helm ont déjà `vault.enabled: false` (pas de sidecar Vault Agent Injector) ; l’application se connecte directement à Vault via Spring Cloud Vault.

#### Option A : Vault Agent Injector (sidecar)

Si `vault.enabled: true` était activé, le sidecar écrirait toutes les clés du secret dans `/vault/secrets/application.properties`. Il suffirait d’ajouter `mongodb-uri` dans le secret Vault ; le template existant itère déjà sur toutes les clés.

#### Option B : Spring Cloud Vault (utilisée actuellement)

L’application lit le secret directement depuis Vault au démarrage. Ajouter `mongodb-uri` dans le même secret que `my-secret`.

**Commande Vault (exemple dev)** :

```bash
vault kv put secret/app/saga/dev/site-service \
  my-secret="valeur-existante" \
  mongodb-uri="mongodb://saga-user:SECRET_PASSWORD@mongodb-mongodb:27017/saga"
```

> **Sécurité** : générer un mot de passe fort pour `SECRET_PASSWORD`.

Pour staging/prod, utiliser les chemins `app/saga/staging/site-service` et `app/saga/prod/site-service` et adapter la configuration Spring Cloud Vault dans les profils (`application-staging.yml`, `application-prod.yml`).

---

### 4. Helm — configuration des values

**Pas de modification des templates K8s** : tout passe par le ConfigMap (`.Values.env`) ou par Vault.

#### 4.1 Activation du profil mongo

Ajouter dans les values Helm (`values-dev.yaml`, etc.) :

```yaml
env:
  SPRING_PROFILES_ACTIVE: "dev"
  SPRING_PROFILES_INCLUDE: "mongo"   # Inclure le profil mongo
  VAULT_ADDR: "http://84.234.25.73:30200"
  # ... reste inchangé
```

> Si `SPRING_PROFILES_ACTIVE` contient déjà plusieurs profils, inclure `mongo` dans la liste ou utiliser `SPRING_PROFILES_INCLUDE` selon la convention du projet.

#### 4.2 Aucune variable MongoDB dans le ConfigMap

Toutes les configs MongoDB (y compris l’URI complète) viennent de Vault. Pas de `SPRING_DATA_MONGODB_HOST` ni de valeurs par défaut dans l’application.

---

### 5. Points d’attention intégrés au plan

Ces contraintes font partie du plan et doivent être respectées lors de l’implémentation.

| Point | Mesure à prendre |
|-------|------------------|
| **Ordre de démarrage** | MongoDB doit être prêt avant `site-service`. Configurer `spring.data.mongodb.connection-timeout` et retry si nécessaire ; utiliser le `readinessProbe` MongoDB côté infra. |
| **Santé** | Activer `management.health.mongo.enabled: true` dans `application-mongo.yml` pour que `/actuator/health` reflète l’état MongoDB. |
| **Tests** | Utiliser Testcontainers MongoDB pour les tests d’intégration ; surcharger `mongodb-uri` en test (ex. via `@DynamicPropertySource` ou `application-test.yml` avec une URI locale). |
| **Réseau K8s** | S’assurer que le Service MongoDB est joignable depuis les pods `site-service` (même namespace ou DNS inter-namespace). |
| **Sécurité** | Ne jamais logger l’URI ou le mot de passe MongoDB. |
| **Profile mongo réutilisable** | Garder `application-mongo.yml` découplé pour que d’autres services puissent l’importer ; ne pas inclure de config spécifique à `site-service`. |

---

### 6. Checklist d’implémentation

**Code**
- [ ] Dépendance `spring-boot-starter-data-mongodb-reactive` dans `site-service/pom.xml`
- [ ] Fichier `application-mongo.yml` avec `spring.data.mongodb.uri: ${mongodb-uri}` (sans valeur par défaut)
- [ ] Profil `mongo` inclus dans les profils actifs (dev/staging/prod) via Helm
- [ ] Classe `Metadata` avec `@Id String id`, `editor`, `app_version`, `manager`, `description`
- [ ] Interface `MetadataRepository extends ReactiveMongoRepository<Metadata, String>`
- [ ] Endpoint `GET /api/site/metadata` → `findAll()`
- [ ] Endpoint `GET /api/site/metadata/{id}` → `findById(id)`
- [ ] Endpoint `POST /api/site/metadata` → `save(metadata)`
- [ ] `management.health.mongo.enabled: true` dans `application-mongo.yml`

**Vault**
- [ ] Ajouter `mongodb-uri` dans le secret `secret/data/app/saga/<env>/site-service`
- [ ] Vérifier que la policy Vault autorise la lecture de cette clé

**Helm**
- [ ] `SPRING_PROFILES_INCLUDE: mongo` (ou équivalent) dans les values par environnement
- [ ] Aucune variable MongoDB dans le ConfigMap (tout depuis Vault)

**Validation**
- [ ] `curl http://<site-service>/api/site/metadata` retourne le contenu de la collection
- [ ] `curl http://<site-service>/api/site/metadata/{id}` retourne un document par id
- [ ] `POST /api/site/metadata` crée un document ; `GET` le retrouve
- [ ] `/actuator/health` indique `mongo: UP`

---

### 7. Ordre d’exécution recommandé

1. Configurer le secret Vault avec `mongodb-uri`.
2. Créer `application-mongo.yml` et inclure le profil `mongo` dans les values Helm.
3. Implémenter le code (dépendance, document, repository, endpoints).
4. Déployer `site-service` et vérifier les endpoints.

---

### 8. Exemple de structure finale

```
site-service
├── Metadata.java              # @Document(collection = "metadata"), @Id String id
├── MetadataRepository.java    # ReactiveMongoRepository<Metadata, String>
├── SiteController.java        # GET /metadata, GET /metadata/{id}, POST /metadata
└── src/main/resources/
    ├── application.yml        # config de base
    ├── application-dev.yml    # spring.profiles.include: mongo
    └── application-mongo.yml  # spring.data.mongodb.uri: ${mongodb-uri}

Vault secret (app/saga/dev/site-service)
├── my-secret
└── mongodb-uri
```

---

### 9. Exemple cURL pour POST

```bash
curl -X POST http://site-service.saga-k8s.local/api/site/metadata \
  -H "Content-Type: application/json" \
  -d '{
    "editor": "ACME Corp",
    "app_version": "1.0.0",
    "manager": "John Doe",
    "description": "Sample metadata for integration test"
  }'
```

> Adapter l’URL selon l’environnement (host, namespace, ingress). Les champs JSON (`editor`, `app_version`, `manager`, `description`) correspondent aux attributs du document `Metadata`.

---

### 10. Profils communs (évolutif)

À terme, un module `common` ou un profil partagé pourra exposer `application-mongo.yml` afin que tout service nécessitant MongoDB puisse importer ce profil sans duplication. Pour l’instant, le fichier reste dans `site-service` et peut être extrait vers un module commun lors de la création des profils communs.
