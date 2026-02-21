---
description: Génère un Dockerfile multi-stage pour service Spring Boot (Maven)
agent: build
model: composer
---

# Dockerfile — Génération de Dockerfile pour services Spring Boot

Génère un **Dockerfile multi-stage** (Maven + JRE) pour un ou plusieurs services Maven/Spring Boot dans un projet multi-modules.

---

## Paramètres

Trois façons de fournir les paramètres :

### 1. Fichier de config via @

`/dockerfile @config.json`

```json
{
  "services": ["site-service", "uaa-service"]
}
```

### 2. JSON inline

`/dockerfile {"services":["site-service","uaa-service","api-service"]}`

### 3. Texte libre

`/dockerfile site-service uaa-service`

---

**Priorité** : fichier @ > JSON inline > texte libre. Valeurs par défaut si rien n'est fourni : `services = [site-service, uaa-service]`.

---

## Structure attendue

Projet Maven multi-modules avec :
- `pom.xml` à la racine (packaging `pom`, déclare `<modules>`)
- `{service}/pom.xml` pour chaque service/module

---

## Règles clés

### 1. Copier TOUS les POMs (reactor Maven)

Le parent POM déclare `<modules>`. Maven a besoin de **tous les pom.xml** pour résoudre la structure du reactor, même pour builder un seul service. On copie donc les POMs de **tous** les modules, mais on ne copie les **sources** que des modules nécessaires au build.

### 2. Sources = uniquement le service + ses dépendances internes

Lire `{svc}/pom.xml`. Si une dépendance a le même `groupId` que le parent et son `artifactId` est un module listé dans le parent, c'est une dépendance interne → copier ses sources aussi.

### 3. Build avec `-pl` et `-am`

Utiliser `mvn package -pl {svc} -am -Dmaven.test.skip=true -B` pour builder uniquement le service et ses dépendances internes.

---

## Template Dockerfile

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# All POMs (Maven needs the full reactor structure)
COPY pom.xml .
COPY {module1}/pom.xml {module1}/
COPY {module2}/pom.xml {module2}/
COPY {moduleN}/pom.xml {moduleN}/
# ... un COPY par module déclaré dans le parent POM

# Sources only for {svc} and its internal dependencies
COPY {dep-interne}/src {dep-interne}/src/
COPY {svc}/src {svc}/src/

RUN mvn package -pl {svc} -am -Dmaven.test.skip=true -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /build/{svc}/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## Exemples concrets

**site-service** (dépend de saga-transaction-lib) :
```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# All POMs
COPY pom.xml .
COPY saga-transaction-lib/pom.xml saga-transaction-lib/
COPY site-service/pom.xml site-service/
COPY uaa-service/pom.xml uaa-service/

# Sources for site-service + saga-transaction-lib
COPY saga-transaction-lib/src saga-transaction-lib/src/
COPY site-service/src site-service/src/

RUN mvn package -pl site-service -am -Dmaven.test.skip=true -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=build /build/site-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**uaa-service** (pas de dépendance module interne) :
```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# All POMs
COPY pom.xml .
COPY saga-transaction-lib/pom.xml saga-transaction-lib/
COPY site-service/pom.xml site-service/
COPY uaa-service/pom.xml uaa-service/

# Sources for uaa-service only
COPY uaa-service/src uaa-service/src/

RUN mvn package -pl uaa-service -am -Dmaven.test.skip=true -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=build /build/uaa-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## .dockerignore

Vérifier qu'un `.dockerignore` existe à la racine du projet pour exclure les artefacts de build :

```
**/target/
.git/
.idea/
**/*.md
**/.cursor/
```

---

## Fichier créé

Pour chaque service : `{svc}/Dockerfile` à la racine du module.

---

## Commande de build (à indiquer dans le résumé)

```bash
# Depuis la racine du projet
docker build -f site-service/Dockerfile -t saga-k8s/site-service:latest .
docker build -f uaa-service/Dockerfile -t saga-k8s/uaa-service:latest .
```

---

## Exécution

1. Lis les paramètres
2. Lis le parent `pom.xml` pour identifier **tous les modules**
3. Pour chaque service demandé, vérifie que `{svc}/pom.xml` existe
4. Identifie les dépendances internes (même `groupId`, `artifactId` = module du parent)
5. Génère `{svc}/Dockerfile` :
   - COPY pom.xml de **tous** les modules (reactor)
   - COPY src/ uniquement du service + ses dépendances internes
   - `mvn package -pl {svc} -am -Dmaven.test.skip=true -B`
6. Vérifie que `.dockerignore` existe (sinon le créer)
7. Affiche un résumé avec les commandes `docker build`
