# Cursor – règles et commandes

## Règles (`rules/`)

- Contexte projet et conventions Java/Saga, appliquées automatiquement selon les fichiers ouverts.

## Commandes slash (`.cursor/commands/`)

Les fichiers `.md` dans `commands/` deviennent des **commandes personnalisées** : en tapant `/` dans le chat Cursor, tu vois la liste des commandes disponibles.

| Commande | Description |
|----------|-------------|
| `/my` | Commande perso : ce que tu écris après est exécuté dans le contexte Saga (ex. `/my run tests`, `/my build`) |
| `/saga-build` | Compiler le projet et donner les commandes pour lancer uaa + site-service + curl |
| `/saga-test` | Lancer les tests d’intégration et voir les logs des steps |
| `/saga-steps` | Résumer les 3 étapes, où est le code, ordre des compensations |
| `/saga-compensation` | Expliquer les compensations et vérifier le code |
| `/saga-add-step` | Ajouter une nouvelle étape à la Saga |
| `/saga-curl` | Donner les curl pour succès et failIndexation=true |
| `/init-k8s` | Init projet K8s natif → `k8s/{base}/`. Paramètres : `@config.json`, JSON inline, ou texte libre |
| `/init-k8s-helm` | Init projet K8s Helm → `k8s/{base}-helm/`. Mêmes formats de paramètres |
| `/dockerfile` | Génère Dockerfile multi-stage (Maven + JRE) pour services Spring Boot. Paramètres : `@config.json` ou liste de services |
| `/dockerpush` | Build et push une image Docker. Paramètres : service, version, compte (ex. `/dockerpush site-service 1.0.0 andr7w`) |

**Utilisation** : dans le chat, tape `/` puis le nom de la commande (ex. `/saga-build`). Tu peux ajouter du texte après : `/saga-add-step pour une étape de notification`.

**Ajouter une commande** : créer un fichier `.md` dans `.cursor/commands/` (ex. `ma-commande.md`) ; le nom du fichier (sans .md) devient la commande `/ma-commande`. Le contenu du fichier est le prompt envoyé à l’IA.

## Prompts à la main (`COMMANDS.md`)

Liste de prompts à copier-coller si tu préfères ne pas utiliser les slash commands.
