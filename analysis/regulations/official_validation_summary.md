# Validation réglementations - synthèse de travail

## Portée
- Source interne analysée : `offline-data/full/room-import/regulation_texts.json`
- Réglementations marquées `Actif` dans le dataset : **241**
- Fichier d'inventaire produit : `active_regulations_inventory.csv`
- Fichier de priorisation produit : `active_regulations_priority_review.csv`

## Ce qui a pu être vérifié automatiquement / semi-manuellement
### Confirmation officielle trouvée
1. **ID 508 - Arrêté préfectoral (Lozère) - 26 oct 2016**
   - Source officielle : `https://www.lozere.gouv.fr/Actions-de-l-Etat/Jeunesse-Engagement-Vie-associative-et-Sport/Sport/Sports-de-pleine-nature/Pratique-du-Canyoning-en-Lozere`
   - État observé : page préfectorale toujours publique, mise à jour le `14/11/2019`
   - Contenu observé : la page confirme explicitement "L'arrêté préfectoral signé le 26 octobre 2016 réglemente la pratique du canyonisme en Lozère" et fournit un lien vers le PDF officiel.
   - Conclusion : **probablement toujours active** (confirmation officielle positive, mais pas de contrôle article par article du PDF dans cette passe).

### Lot géographique examiné : Lozère
- Le site officiel de la préfecture de Lozère confirme encore l'existence et l'applicabilité de l'arrêté du **26 octobre 2016** sur la pratique du canyonisme.
- Dans le dataset embarqué, les textes récents liés à la Lozère observés sont :
  - **ID 508** - `Actif` - Arrêté préfectoral (Lozère) - 26 oct 2016
  - **ID 540** - `Abrogé` - Arrêté municipal (Pied-de-Borne, Prévenchères) - 26 avr 2024
  - **ID 557** - `Obsolète` - Arrêté préfectoral (Lozère) - 05 sept 2024
- À ce stade, je n'ai **pas trouvé de texte officiel manquant** à ajouter pour la Lozère avec un niveau de confiance suffisant.
- En revanche, le lot Lozère montre qu'il faut comparer systématiquement les textes anciens actifs avec les pages préfectorales actuelles, car des textes récents `Obsolète` / `Abrogé` existent déjà dans le dataset autour du même territoire.

## Limites rencontrées
- Une validation exhaustive des **241** textes actifs sur des sites officiels (mairies, préfectures, Legifrance, régions, parcs) nécessite une recherche web systématique.
- Les moteurs généralistes déclenchent rapidement des CAPTCHA/anti-bot, ce qui empêche une automatisation fiable à grande échelle depuis cet environnement.
- Le dataset embarqué contient souvent des miroirs `static.descente-canyon.com` plutôt que des liens officiels directs.

## Priorités de revue manuelle proposées
Le fichier `active_regulations_priority_review.csv` trie les textes actifs à vérifier en priorité selon :
- absence de date d'effet
- ancienneté du texte (<= 2010)
- absence de pièce jointe
- nombre de canyons impactés

Les plus critiques à revoir en premier sont donc les textes :
- très anciens mais encore `Actif`
- sans PDF joint ni date explicite
- impactant beaucoup de canyons

## Recherche de réglementations potentiellement manquantes
Le fichier `missing_regulations_candidates.md` reste séparé pour validation humaine.
Pour l'instant, aucun candidat nouveau n'a été ajouté automatiquement avec un niveau de confiance suffisant, faute de crawl officiel exhaustif sans CAPTCHA.

## Recommandation
Procéder en **lots thématiques ou géographiques** (ex. `Lozère`, `Var`, `Alpes-Maritimes`, `Corse-du-Sud`, `Alpes-de-Haute-Provence`) pour permettre une validation réellement fiable via sources officielles.

## Prochaine priorité recommandée
1. **Var** : un texte très récent et très impactant existe déjà dans le dataset (`ID 579 - Arrêté préfectoral (Var) - 13 mai 2025`, 84 canyons liés dans l'inventaire top-link).
2. **Alpes-Maritimes** : plusieurs arrêtés actifs historiques sont très impactants et méritent une confirmation officielle à jour.
3. **Alpes-de-Haute-Provence** : un arrêté ancien mais très lié (`ID 42`) doit être revalidé ou remplacé par un texte plus récent si disponible.
