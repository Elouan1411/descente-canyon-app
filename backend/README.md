# Descente Canyon - Backend PDF (Vercel Serverless)

Ce projet héberge l'API de synchronisation des documents PDF communautaires pour chaque canyon, avec stockage sur **Vercel Blob** et métadonnées sur **Vercel Postgres**.

---

## 🚀 Guide de Déploiement Rapide sur Vercel (100% Gratuit sans carte bancaire)

### 1. Importer sur Vercel
1. Connectez-vous sur [Vercel](https://vercel.com).
2. Cliquez sur **Add New** ➔ **Project** et sélectionnez votre dépôt GitHub `descente-canyon-app`.
3. Dans la configuration du projet :
   - **Root Directory** : définissez `backend`
   - **Framework Preset** : Next.js

### 2. Activer les services gratuits Vercel
Dans votre tableau de bord Vercel pour ce projet :
1. **Stockage PDF (Vercel Blob)** :
   - Allez dans l'onglet **Storage** ➔ **Create** ➔ **Blob** (Gratuit sans carte bancaire).
   - Cliquez sur **Connect to Project** (Vercel injecte automatiquement la variable `BLOB_READ_WRITE_TOKEN`).
2. **Base de Données (Vercel Postgres)** :
   - Allez dans l'onglet **Storage** ➔ **Create** ➔ **Postgres** (Gratuit sans carte bancaire).
   - Cliquez sur **Connect to Project** (Vercel injecte automatiquement la variable `POSTGRES_URL`).

### 3. Variables d'Environnement (Sécurité APK)
Dans **Settings** ➔ **Environment Variables**, ajoutez :
- `APP_SECRET` : Clé secrète partagée avec l'application Android (ex: `descente_canyon_secret_key_2026`).
- `APK_SIGNATURE_HASH` : Empreinte SHA-256 du certificat de signature de votre APK officielle.
- `SKIP_AUTH` : (`false` en production, `true` pendant vos tests locaux si besoin).

---

## 📡 Endpoints API

- `GET /api/canyons/[canyonId]/pdfs` : Liste les PDF enregistrés pour un canyon.
- `POST /api/canyons/[canyonId]/pdfs` : Envoi d'un nouveau PDF (multipart `file`, max 100 Mo).
- `DELETE /api/pdfs/[pdfId]` : Supprime un PDF.
