# Plan d'article : Saga en orchestration – création d'un site client

---

## 1. Le pattern Saga et le choix de l'orchestration




Quand plusieurs services autonomes doivent travailler ensemble, on ne peut pas s'appuyer sur une grosse transaction unique : chaque service a son modèle, sa base, ses règles. Faire un rollback technique sur tout le système ? On oublie. Le **pattern Saga** part de là : une succession de transactions locales, chacune avec une **action de compensation** si quelque chose casse plus loin. L'enjeu n'est pas de revenir en arrière techniquement, mais de préserver la **cohérence métier** du système.

On peut mettre une Saga en œuvre de deux manières : en **chorégraphie** (chaque service décide quoi faire ensuite) ou en **orchestration** (un pilote central enchaîne les étapes). Ici on retient l'orchestration. Pas par préférence technique : parce que le processus qu'on modélise — la création d'un site client — est une séquence ordonnée, avec des validations croisées et des règles de gouvernance qu'on veut explicites. En centralisant le pilotage, on rend ces règles visibles, traçables, auditables, au lieu de les noyer dans chaque service.

Dans une Saga orchestrée, un composant dédié enchaîne les étapes, suit l'état courant et déclenche les compensations quand il faut. Les services métier restent autonomes et transactionnels chez eux ; ils ne portent pas la cohérence globale. Cette séparation compte : la complexité du workflow reste à un endroit, au lieu de se diffuser partout.

---

## 2. Contexte métier : création d'un site client multi-référentiels

Le cas qu'on étudie : **créer un site client** dans un SI B2B. Un site, c'est une entité opérationnelle concrète — une agence, un site industriel, un chantier, une unité rattachée à une entreprise. Le créer ne revient pas à « persister une ligne en base » : il faut l'inscrire correctement dans **plusieurs référentiels** indépendants.

Avant qu'un site soit vraiment utilisable, il doit être rattaché à une entreprise existante, associé à des acteurs responsables identifiés, protégé par des règles d'accès explicites, et visible dans les outils de recherche internes. Chaque aspect est géré par un service à part, avec ses contraintes et son cycle de vie. Aucun de ces services ne détient à lui seul la définition d'un « site valide » au sens métier.

L'invariant qui compte : **un site n'existe (au sens métier) que s'il est à la fois gouverné, sécurisé et publiable.** Un site à moitié configuré ne doit jamais être visible ni exploitable. C'est cet invariant qui justifie la Saga : elle ne garantit pas l'existence technique du site, mais **la validité de son existence**.

---

## 3. Déroulement logique de la Saga

La Saga de création de site s'articule en **quatre étapes**.

D'abord la **validation des référentiels amont** — entreprise, partenaires humains, etc. Une fois ces préconditions satisfaites, le site peut être **créé** dans un état transitoire. Ensuite on applique les **règles d'accès** : qui peut voir et administrer ce site. Enfin l'**indexation** : l'acte de publication, qui rend le site visible dans la recherche et la navigation.

Chaque étape est transactionnelle localement, mais aucune ne suffit à elle seule. Ce n'est que quand toute la chaîne a réussi que le site peut être considéré comme actif.

---

## 4. Les erreurs dans ce contexte

Ici, les échecs ne viennent pas d'un bug ponctuel ou d'une panne. Ils tiennent à la façon dont les référentiels vivent chacun de leur côté : l'entreprise peut être devenue inactive, un partenaire avoir perdu son périmètre, les données être un peu décalées entre services. Ça arrive tout le temps quand chaque référentiel évolue en autonomie.

À l'étape de création du site, on peut avoir des échecs locaux, mais le vrai sujet c'est l'état qu'on laisse derrière soi : un site créé sans être ensuite sécurisé ni publié n'a pas de valeur métier. Les ACL, elles, sont particulièrement sensibles — règles de sécurité, hiérarchies d'accès, cohérence des référentiels humains. Si ça casse là, le site ne peut plus être gouverné correctement ; il existe peut-être en base, mais il est invalide et il faut le compenser. Enfin l'indexation, l'acte de publication : elle peut refuser un objet incomplet ou non sécurisé. Un site non indexé reste invisible ; le laisser tel quel crée une incohérence qu'on aura du mal à rattraper plus tard.

Dans tous ces cas, la Saga ne vise pas à « réparer un peu » : elle ramène à un invariant simple — soit le site est pleinement valide, soit il n'existe pas.

---

## 5. (À rédiger) Compensations et gestion des états

*À venir : comment on déclenche les compensations, dans quel ordre, et comment on représente les états de la Saga (en cours, compensée, terminée).*

---

## 6. (À rédiger) Pourquoi un simple workflow asynchrone ne suffit pas

*À venir : en conclusion, ce qui distingue un workflow « fire and forget » d'une Saga avec compensations et invariant métier.*

---

## 7. (Optionnel) Version « case study » pour Medium / blog tech

*Idée : reprendre le même contenu en case study plus narrative — problème de départ, vision naïve, rappel sur la Saga orchestrée, anti-pattern (microservices découpés pour la Saga), repenser la structure (bounded contexts, moins de services artificiels), conclusion « la Saga au service du design ».*

---

On élargira ce plan au fur et à mesure pour une meilleure implémentation.
