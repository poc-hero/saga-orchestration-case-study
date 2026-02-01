# saga-orchestration-case-study
Case study illustrating the Saga orchestration pattern through a real-world B2B site creation workflow, focusing on business invariants, failure scenarios, and compensations.

---

## Démo Java réactive (multi-modules)

Packages : **com.pocmaster** (applications), **com.lib.pocmaster** (lib Saga).

| Module | Rôle |
|--------|------|
| **saga-transaction-lib** | Lib réactive Saga (`com.lib.pocmaster.saga`) : `SagaExecutor`, `SagaStep`, `TransactionContext`. Autoconfiguration activée par **@EnableSagaExecutor**. |
| **site-service** | Service site (`com.pocmaster.site`) : composante validation, création du site, endpoint Saga qui enchaîne validation → création → appel UAA (port 9090). |
| **uaa-service** | Service UAA (`com.pocmaster.uaa`) : règles d'accès / ACL (port 9091). |

Pour activer les composants Saga dans une application, annoter la classe principale avec **@EnableSagaExecutor** (ex. `SiteApplication`).

### Lancer la démo

1. Compiler : `mvn clean install -DskipTests`
2. Démarrer uaa-service : `mvn -pl uaa-service spring-boot:run`
3. Démarrer site-service : `mvn -pl site-service spring-boot:run`
4. Appel de la Saga :  
   `curl -X POST http://localhost:9090/api/site/create -H "Content-Type: application/json" -d '{"companyId":"c1","siteName":"Mon site"}'`

En cas d'échec sur une étape (ex. UAA), site-service exécute les compensations dans l'ordre inverse (UAA → site → validation).
