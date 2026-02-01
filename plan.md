## Saga Orchestration

---

### Le pattern Saga et le choix de l'orchestration

Le pattern Saga intervient dans un système distribué lorsque plusieurs services doivent participer à l’exécution d’une transaction (souvent globale).
La contrainte ici est que chaque service possède son système de transaction locale, qui n’est pas forcément compatible avec celui des autres, dans un contexte bien défini.

L’objectif est de pouvoir garantir que le flux d’exécution soit coherent et surtout lorsqu'un comportement inattendu survient lors de l'execution de la requete.
Pour y arriver, on va mettre sur pied un système que l’on appelle Saga Transaction.

Le Design Pattern Saga peut être implémenté suivant deux modes :

* **Le mode Chorégraphie** :
  Ici, les différents services participants à la transaction communiquent via des événements et sont responsables de publier des `ActionEvent` et des `FailedEvent` afin que les souscripteurs puissent prendre action et garantir l’équilibre du système.

* **Le mode Orchestration** :
  Ici, on a un composant qui est chargé d’orchestrer l’exécution des différents services et, de ce fait, de garantir l’équilibre du système.

---

### Cas pratique : création d’un site client multi-référentiels

* On souhaite créer un site B2B
* À la création du site, il doit avoir des règles d’administration du site (ACL) bien définies
* Le site doit être correctement indexé pour être fonctionnel pour l’ensemble du système

---

### Justification de l’utilisation

* La Saga, en général, est un pattern qui prend son sens dans un environnement distribué. Autrement, il suffirait de mettre en place un système transactionnel traditionnel avec un seul service
* Dans notre cas, nous avons une fonctionnalité de création de site B2B qui n’a de sens que lorsque la création, l’attribution des ACL et l’indexation ont réussi **(A)**
* En cas d’échec lors d’une des trois étapes, peu importe le stade, les executions precedentes doivent compensees (ex. si l’indexation du site échoue, les ACL doivent être révoqués et le site supprimé). On ne veut pas de site orphelin **(B)**
* D’autre part, le système présente plusieurs services (`site-service`, `uaa-service`) **(C)**

Les énoncés **A**, **B** et **C** ci-dessus justifient techniquement la raison de l’utilisation du Design Pattern Saga.
Dans cette partie de l’article, nous allons utiliser la **Saga Orchestration**.

---

### La Saga Orchestration

Nous aurons les composants suivants :

* **Site-service** : ce service permet la gestion du site web. Pour ce qui nous concerne, la création du site et l’indexation
* **Uaa-service** : ce service permet la gestion des authentifications et des autorisations. Pour ce qui nous concerne, la création des autorisations ou règles d’accès au site
* **Saga-orchestration-lib** : c’est la librairie qui encapsule la logique d’orchestration
* Le tout sera déclenché via un endpoint de création de site exposé dans le `site-service`

Le schéma ci-dessous illustre la Saga Orchestration en action dans notre contexte.

---

### La compensation avec Saga

La compensation est une étape essentielle dans une transaction Saga.
Elle est équivalente au rollback dans une transaction traditionnelle.

Dans la Saga Orchestration, comme vu précédemment, chaque élément transactionnel est implémenté en deux parties.
Pour l’élément transactionnel de création des ACL, par exemple, l’action consiste à créer une ACL et la compensation consiste à supprimer l’ACL.

---

### Implémentation de la Saga Orchestration

*(capture de l’arborescence)*

#### Modèle d’exécution de chaque étape de nos unités transactionnelles

```java
public record MultiflexSagaStep<I, O>(
        Function<I, Mono<O>> action,
        Function<O, Mono<Void>> compensation,
        boolean transactionalAction,
        boolean transactionalCompensation
) {}
```

Chaque step comprend :

* **action** : la fonction qui exécute l’action. Elle produit un résultat qui servira d’input à la prochaine step et à la compensation.
  Si `transactionalAction = false`, l’étape ne s’exécute pas.

* **compensation** : la fonction qui compense l’exécution de l’action.
  Elle sera appelée automatiquement à l’étape *n* ou *n+1* en cas d’échec.
  Si `transactionalCompensation = false`, l’étape ne s’exécute pas.

---

### Exemple d’orchestration

```java
public Mono<Void> run(CreateSiteRequest request) {

    SagaStep<CreateSiteRequest, ValidationResult> validateReferences =
        new SagaStep<>(
            this::validateCompanyAndActors,
            this::compensateValidation,
            true,
            true
        );

    SagaStep<ValidationResult, SiteCreated> createSite =
        new SagaStep<>(
            this::createSite,
            this::deleteSite,
            true,
            true
        );

    SagaStep<SiteCreated, SiteWithAcl> applyAcl =
        new SagaStep<>(
            this::applyAccessRules,
            this::removeAccessRules,
            true,
            true
        );

    SagaStep<SiteWithAcl, Void> indexSite =
        new SagaStep<>(
            this::indexSite,
            this::removeIndex,
            true,
            true
        );

    return sagaExecutor
        .newExecution(request)
        .addStep(validateReferences)
        .addStep(createSite)
        .addStep(applyAcl)
        .addStep(indexSite)
        .execute();
}
```

---

### Explication

```java
return sagaExecutor
    .newExecution(request)   // Initialise une nouvelle instance de Saga avec la requête métier comme contexte initial
    .addStep(step1)          // Enregistre la première étape de la Saga ; son résultat devient l’entrée de l’étape suivante
    .addStep(step2)          // Ajoute une étape dépendante de la précédente, exécutée uniquement si celle-ci a réussi
    .addStep(step3)          // Étape d’attribution des ACL
    .addStep(step4)          // Étape d’indexation du site
    .execute();              // Lance l’exécution séquentielle et déclenche les compensations en cas d’échec
```

---

### Code source

Pour avoir l’intégralité du code source, voici le lien du dépôt GitHub :
[https://github.com/poc-hero/saga-orchestration-case-study](https://github.com/poc-hero/saga-orchestration-case-study)
