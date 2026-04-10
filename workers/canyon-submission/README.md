# Canyon Submission Worker

Worker Cloudflare pour recevoir un formulaire public et creer une branche, une draft PR, puis une issue publique de suivi sur GitHub.

## Structure

- `src/index.js`: endpoint `POST /submit-canyon`
- `wrangler.jsonc`: configuration du Worker
- `package.json`: scripts utilises par Cloudflare Builds

## Variables runtime a configurer dans Cloudflare

Dans `Settings > Variables & Secrets` du Worker:

- `GITHUB_OWNER`: owner du repo GitHub cible
- `GITHUB_REPO`: repo GitHub cible
- `ALLOWED_ORIGINS`: liste CSV des origins autorisees
  - exemple: `https://<user>.github.io,https://<user>.github.io/descente-canyon-app`
- secret `GITHUB_TOKEN`: token du bot avec permissions `Contents: Read and write`, `Pull requests: Read and write` et `Issues: Read and write`

Les labels `new-canyon` et `overlay-dc` sont utilises si presents dans le repo cible. En leur absence, le Worker cree quand meme la PR et l'issue sans labels.

`keep_vars` est active dans `wrangler.jsonc` pour eviter qu'un deploy Git n'efface ces variables runtime.

## Cloudflare Git-connected configuration

Utilise ces valeurs quand tu connectes ce repo a ton Worker dans Cloudflare:

- Build command: laisser vide
- Deploy command: `npm run deploy`
- Non-production branch deploy command: `npm run deploy:preview`
- Root directory: `workers/canyon-submission`
- API token: laisser vide et utiliser le token auto-genere par Cloudflare
- Build variables: aucune
- Build caching: valeur par defaut, pas de besoin particulier ici

Important: le `name` dans `wrangler.jsonc` doit correspondre exactement au nom du Worker existant dans le dashboard Cloudflare.

## Local development

Node 20.3+ est recommande pour correspondre a la version requise par `wrangler`.

```bash
npm install
npm run dev
```

## Endpoints

- `GET /health`
- `POST /submit-canyon`
