# Guide d'intégration MongoDB avec site-service

Objectif : ajouter un accès MongoDB au `site-service` pour valider la connectivité à une base MongoDB déployée sur le cluster Kubernetes, en mode sample.

---

## Contexte et périmètre

- **Collection** : `metadata` (attributs : `_id` géré par MongoDB, `editor`, `app_version`, `manager`, `description`)
- **Endpoints** :
  - `GET /api/site/metadata` — affiche tout le contenu de la collection
  - `GET /api/site/metadata/{id}` — affiche un document par son `_id`
  - `POST /api/site/metadata` — crée un nouveau document
- **Configuration** : tout via Vault (Spring Cloud Vault) ; aucune valeur par défaut pour l'environnement dev
- **Profil** : `application-mongo.yml` (profil réutilisable pour les services futurs nécessitant MongoDB)
- **Déploiement** : modifications uniquement dans les Helm values existants (pas de manifests K8s natifs)

---

## Plan d'implémentation

### 1. Application Java (site-service)

| Étape | Fichier / Action | Description |
|-------|------------------|-------------|
| 1.1 | `pom.xml` | Ajouter `spring-boot-starter-data-mongodb-reactive` |
| 1.2 | `application.yml` | Exclure l'autoconfiguration MongoDB par défaut (voir section 2) |
| 1.3 | `application-mongo.yml` | Propriétés MongoDB (uri depuis Vault) + réactivation de l'autoconfiguration + health ; profil activé via `SPRING_PROFILES_ACTIVE` dans Helm |
| 1.4 | Document `Metadata` | `@Id String id` (ObjectId Mongo), `editor`, `app_version` (`@Field`), `manager`, `description` |
| 1.5 | Repository `MetadataRepository` | Interface `ReactiveMongoRepository<Metadata, String>` |
| 1.6 | Controller | `GET /api/site/metadata`, `GET /api/site/metadata/{id}`, `POST /api/site/metadata` |

**Document Metadata** : `_id` géré par MongoDB (`@Id` sur un `String` ; MongoDB génère un ObjectId). Le champ Java `appVersion` est mappé vers `app_version` dans MongoDB/JSON via `@Field("app_version")`.

---

### 2. Profil mongo (application-mongo.yml) et autoconfiguration

#### Problème : autoconfiguration MongoDB sans le profil `mongo`

Le starter `spring-boot-starter-data-mongodb-reactive` est dans le `pom.xml`. Même sans le profil `mongo` actif, Spring Boot tente de se connecter à `localhost:27017` (autoconfiguration). En local sans MongoDB, l'app planterait.

#### Solution : exclure par défaut, réactiver avec le profil

**`application.yml`** — exclure l'autoconfiguration MongoDB :

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration
      - org.springframework.boot.autoconfigure.data.mongo.MongoReactiveDataAutoConfiguration
```

**`application-mongo.yml`** — réactiver l'autoconfiguration et configurer l'URI :

```yaml
# application-mongo.yml — profil activé via SPRING_PROFILES_ACTIVE (ex: "dev,mongo")
spring:
  autoconfigure:
    exclude: []
  data:
    mongodb:
      uri: ${mongodb-uri}   # Obligatoire, fourni par Vault — pas de valeur par défaut

management:
  health:
    mongo:
      enabled: true
```

Ainsi : sans le profil `mongo`, pas de tentative de connexion MongoDB. Avec le profil `mongo`, l'autoconfiguration est réactivée et l'URI est résolue depuis Vault.

---

### 3. Activation du profil mongo

#### Contrainte Spring Boot 3.x

`spring.profiles.include` est **interdit** dans les fichiers profile-specific (`application-dev.yml`, etc.). Spring Boot 3.2 lèvera une erreur :

> *"Property 'spring.profiles.include' is not allowed in profile-specific documents"*

#### Approche retenue : `SPRING_PROFILES_ACTIVE` dans les values Helm

L'activation du profil `mongo` se fait côté Helm, dans `SPRING_PROFILES_ACTIVE` :

```yaml
env:
  SPRING_PROFILES_ACTIVE: "dev,mongo"
```

Chaque service déclare les profils dont il a besoin dans ses values Helm. Un service sans MongoDB omet simplement `mongo` de la liste.

> Les détails sur la gestion des profils par environnement seront approfondis dans la section GitOps.

---

### 4. Vault — stockage des credentials MongoDB

Toutes les configurations MongoDB sont stockées dans Vault. Aucune valeur par défaut dans les configs applicatives pour dev/prod.

**Option retenue** : Spring Cloud Vault. Les configs Helm ont déjà `vault.enabled: false` (pas de sidecar Vault Agent Injector) ; l'application se connecte directement à Vault via Spring Cloud Vault.

#### Option A : Vault Agent Injector (sidecar)

Si `vault.enabled: true` était activé, le sidecar écrirait toutes les clés du secret dans `/vault/secrets/application.properties`. Il suffirait d'ajouter `mongodb-uri` dans le secret Vault ; le template existant itère déjà sur toutes les clés.

#### Option B : Spring Cloud Vault (utilisée actuellement)

L'application lit le secret directement depuis Vault au démarrage. Ajouter `mongodb-uri` dans le même secret que `my-secret`.

**Commande Vault** — utiliser `kv patch` pour ajouter sans écraser les clés existantes :

```bash
vault kv patch secret/app/saga/dev/site-service \
  mongodb-uri="mongodb://saga-user:SECRET_PASSWORD@mongodb-mongodb:27017/saga"
```

> **`kv patch` vs `kv put`** : `kv put` remplace l'intégralité du secret (si `my-secret` est omis, il sera supprimé). `kv patch` ajoute ou modifie uniquement les clés spécifiées.

> **Sécurité** : générer un mot de passe fort pour `SECRET_PASSWORD`.

Pour staging/prod, utiliser les chemins `app/saga/staging/site-service` et `app/saga/prod/site-service`.

---

### 5. Helm — configuration des values

**Pas de modification des templates K8s** : tout passe par le ConfigMap (`.Values.env`) ou par Vault.

#### 5.1 Aucune variable MongoDB dans le ConfigMap

Toutes les configs MongoDB (URI complète avec credentials) viennent de Vault. Le ConfigMap Helm ne contient aucune variable `SPRING_DATA_MONGODB_*`.

#### 5.2 Activation du profil `mongo` via `SPRING_PROFILES_ACTIVE`

Ajouter `mongo` dans `SPRING_PROFILES_ACTIVE` des values Helm :

**`values-dev.yaml`** :

```yaml
env:
  SPRING_PROFILES_ACTIVE: "dev,mongo"
  VAULT_ADDR: "http://84.234.25.73:30200"
  # ... reste inchangé
```

**`values-staging.yaml`** / **`values-prod.yaml`** : idem, adapter le profil d'environnement.

> Les détails sur la gestion des profils par environnement seront approfondis dans la section GitOps.

---

### 6. Mapping des champs : `app_version`

Le document MongoDB utilise `app_version` (underscore), mais la convention Java est camelCase (`appVersion`).

**Solution retenue** : annotation `@Field("app_version")` sur le champ Java :

```java
@Document(collection = "metadata")
public class Metadata {
    @Id
    private String id;
    private String editor;

    @Field("app_version")
    private String appVersion;

    private String manager;
    private String description;
}
```

Le JSON du POST et la base MongoDB utilisent `app_version`. Le code Java utilise `appVersion`.

---

### 7. Points d'attention intégrés au plan

| Point | Mesure à prendre |
|-------|------------------|
| **Ordre de démarrage** | MongoDB doit être prêt avant `site-service`. Configurer un timeout de connexion dans `application-mongo.yml` (`spring.data.mongodb.server-selection-timeout`) et/ou augmenter `initialDelaySeconds` de la readinessProbe. |
| **Health check et readinessProbe** | Avec `management.health.mongo.enabled: true`, si MongoDB est down, `/actuator/health` retourne `DOWN` et la readinessProbe échoue. Augmenter `failureThreshold` dans les values Helm pour éviter les restart loops au démarrage si MongoDB est lent à démarrer. |
| **Tests** | Utiliser Testcontainers MongoDB pour les tests d'intégration ; surcharger `mongodb-uri` en test via `@DynamicPropertySource`. Activer le profil `mongo` dans le test (`@ActiveProfiles({"test", "mongo"})`). |
| **Réseau K8s** | S'assurer que le Service MongoDB est joignable depuis les pods `site-service` (même namespace ou DNS inter-namespace). |
| **Sécurité** | Ne jamais logger l'URI ou le mot de passe MongoDB. |
| **Profil mongo réutilisable** | Garder `application-mongo.yml` découplé ; ne pas inclure de config spécifique à `site-service`. |
| **Autoconfiguration** | Exclure MongoDB par défaut dans `application.yml` ; réactiver dans `application-mongo.yml` (section 2). |

---

### 8. Checklist d'implémentation

**Code**
- [ ] Dépendance `spring-boot-starter-data-mongodb-reactive` dans `site-service/pom.xml`
- [ ] Exclure l'autoconfiguration MongoDB dans `application.yml`
- [ ] Fichier `application-mongo.yml` : réactiver l'autoconfig + `spring.data.mongodb.uri: ${mongodb-uri}` + health
- [ ] Classe `Metadata` avec `@Id String id`, `editor`, `@Field("app_version") String appVersion`, `manager`, `description`
- [ ] Interface `MetadataRepository extends ReactiveMongoRepository<Metadata, String>`
- [ ] Endpoint `GET /api/site/metadata` -> `findAll()`
- [ ] Endpoint `GET /api/site/metadata/{id}` -> `findById(id)`
- [ ] Endpoint `POST /api/site/metadata` -> `save(metadata)`

**Vault**
- [ ] `vault kv patch` pour ajouter `mongodb-uri` dans `secret/data/app/saga/<env>/site-service`
- [ ] Vérifier que la policy Vault autorise la lecture de cette clé

**Helm**
- [ ] Aucune variable MongoDB dans le ConfigMap (tout depuis Vault)
- [ ] `SPRING_PROFILES_ACTIVE: "dev,mongo"` (ou équivalent) dans les values par environnement
- [ ] Vérifier `failureThreshold` de la readinessProbe pour tolérer un démarrage lent de MongoDB

**Validation**
- [ ] `POST /api/site/metadata` crée un document (voir section 10 pour le curl)
- [ ] `GET /api/site/metadata` retourne le contenu de la collection
- [ ] `GET /api/site/metadata/{id}` retourne un document par id
- [ ] `/actuator/health` indique `mongo: UP`

---

### 9. Ordre d'exécution recommandé

1. `vault kv patch` — ajouter `mongodb-uri` dans le secret existant.
2. Implémenter le code : dépendance, exclusion autoconfig, `application-mongo.yml`, document, repository, endpoints.
3. Tests locaux avec Testcontainers.
4. Mettre à jour les values Helm : `SPRING_PROFILES_ACTIVE: "dev,mongo"`, vérifier failureThreshold.
5. Déployer `site-service` et vérifier les endpoints.

---

### 10. Exemple de structure finale

```
site-service/src/main/
├── java/com/pocmaster/site/
│   ├── metadata/
│   │   ├── Metadata.java              # @Document, @Id, @Field("app_version")
│   │   └── MetadataRepository.java    # ReactiveMongoRepository<Metadata, String>
│   └── SiteController.java            # + GET /metadata, GET /metadata/{id}, POST /metadata
└── resources/
    ├── application.yml                # autoconfig MongoDB exclue par défaut
    ├── application-dev.yml            # Spring Cloud Vault config (inchangé)
    └── application-mongo.yml          # autoconfig réactivée + mongodb uri + health

Vault secret (app/saga/dev/site-service)
├── my-secret
└── mongodb-uri
```

---

### 11. Exemples cURL

**POST — créer un document :**

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

**GET — lister tous les documents :**

```bash
curl http://site-service.saga-k8s.local/api/site/metadata
```

**GET — un document par id :**

```bash
curl http://site-service.saga-k8s.local/api/site/metadata/<OBJECT_ID>
```

> Adapter l'URL selon l'environnement (host, namespace, ingress). Les champs JSON (`editor`, `app_version`, `manager`, `description`) correspondent aux attributs du document `Metadata`.

---

### 12. Profils communs (évolutif)

À terme, un module `common` ou un profil partagé pourra exposer `application-mongo.yml` afin que tout service nécessitant MongoDB puisse importer ce profil sans duplication. Pour l'instant, le fichier reste dans `site-service` et peut être extrait vers un module commun lors de la création des profils communs.
