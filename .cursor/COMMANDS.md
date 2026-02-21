# Commandes / prompts Cursor pour le projet Saga

Copier-coller ces prompts dans le chat Cursor pour enchaîner plus vite.

---

## Build & run

- **Compiler tout**  
  `Compile le projet (mvn clean install -DskipTests) et indique les commandes pour lancer uaa-service puis site-service.`

- **Lancer les tests d’intégration**  
  `Lance les tests d’intégration du site-service (mvn -pl site-service test) et explique comment voir les logs des steps dans la console.`

- **Lancer la démo (succès puis compensation)**  
  `Donne les commandes curl pour appeler la Saga en succès puis avec failIndexation=true pour voir les compensations.`

---

## Saga & code

- **Résumer les 3 étapes de la Saga**  
  `Résume les 3 étapes de la Saga (validation+création, ACL, indexation), où est le code, et l’ordre des compensations.`

- **Ajouter une étape à la Saga**  
  `Ajoute une nouvelle étape à la Saga (avec action et compensation), en restant cohérent avec les steps existants et la lib saga-transaction-lib.`

- **Ajouter un log à chaque step**  
  `Ajoute ou vérifie qu’il y a un log.info à chaque exécution de step dans SagaExecutor (avec le nom du step et le transactionId).`

- **Corriger / expliquer les compensations**  
  `Explique comment les compensations sont déclenchées et dans quel ordre ; vérifie que chaque step a bien une compensation qui utilise le résultat de l’action.`

---

## Tests

- **Vérifier les tests d’intégration**  
  `Vérifie que les tests d’intégration couvrent le succès de la Saga et le cas failIndexation=true (compensations) ; propose des corrections si besoin.`

- **Ajouter un test (succès ou compensation)**  
  `Ajoute un test d’intégration pour [succès / échec à l’ACL / failIndexation] en utilisant le WireMock existant.`

---

## K8s (Phase 1 déploiement)

- **Init projet K8s natif**  
  `Initialise la structure base/ pour déploiement K8s natif. Paramètres : @config.json, JSON inline, ou texte libre (ex. saga-k8s site-service uaa-service). Commande autonome, réutilisable sur tout repo.`

- **Init projet K8s Helm**  
  `Initialise les Helm charts (helm/<service>/). Mêmes formats de paramètres. Commande autonome, réutilisable sur tout repo.`

---

## Doc & plan

- **Mettre à jour le README**  
  `Relis le README et mets-le à jour pour qu’il reflète la structure actuelle (modules, étapes, commandes de lancement).`

- **Aligner le plan avec le code**  
  `Compare plan.md (étapes, compensations, erreurs) avec l’implémentation actuelle et propose des ajustements de texte ou de code.`

---

## Raccourcis utiles

- `Où est définie la Saga (les 3 steps) ?` → `SiteCreationSaga` dans site-service.
- `Où est l’exécuteur Saga ?` → `SagaExecutor` dans saga-transaction-lib.
- `Comment forcer l’échec pour tester les compensations ?` → Requête avec `"failIndexation": true`.
