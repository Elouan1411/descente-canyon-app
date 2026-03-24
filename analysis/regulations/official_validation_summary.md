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

### Lot géographique examiné : Var
- Site officiel accessible : `https://www.var.gouv.fr/`
- Recherche interne testée : `https://www.var.gouv.fr/contenu/action?SearchText=canyoning`
- Résultat observé : **0 résultat** pour `canyoning` sur le moteur interne du site préfectoral.
- Dans cette passe, aucune page canyoning/réglementation dédiée n'a pu être isolée automatiquement avec un niveau de confiance suffisant depuis le portail officiel seul.
- Texte prioritaire du dataset à revalider :
  - **ID 579** - `Actif` - Arrêté préfectoral (Var) - 13 mai 2025
- Enjeu : ce texte impacte fortement le dataset (**84 canyons liés** dans l'inventaire), donc c'est le prochain lot prioritaire pour une validation manuelle ciblée.

### Lot géographique examiné : Alpes-Maritimes
- Site officiel accessible : `https://www.alpes-maritimes.gouv.fr/`
- Recherche interne testée : `https://www.alpes-maritimes.gouv.fr/contenu/action?SearchText=canyoning`
- Résultat observé : **1 résultat**, mais il est lié à une *étude d'impact sur l'environnement* et ne permet pas d'identifier une réglementation canyoning exploitable.
- Dans cette passe, aucune page canyoning explicite n'a pu être confirmée automatiquement depuis le portail officiel.
- Textes actifs prioritaires à revalider :
  - **ID 349** - Arrêté préfectoral (Alpes-Maritimes) - 27 oct 2016
  - **ID 153** - Arrêté préfectoral (Alpes-Maritimes) - 09 mai 1990
  - **ID 194** - Arrêté préfectoral (Alpes-Maritimes) - 15 fév 1996
- Conclusion : pas de confirmation officielle positive supplémentaire dans cette passe, mais le lot reste prioritaire.

### Lot géographique examiné : Alpes-de-Haute-Provence
- Site officiel accessible : `https://www.alpes-de-haute-provence.gouv.fr/`
- Recherche interne testée : `https://www.alpes-de-haute-provence.gouv.fr/contenu/action?SearchText=canyoning`
- Résultat observé : **0 résultat** pour `canyoning` sur le moteur interne du site préfectoral.
- Dans cette passe, aucun résultat canyoning dédié n'a pu être identifié automatiquement à partir de la navigation générique du site.
- Textes actifs prioritaires à revalider :
  - **ID 42** - Arrêté préfectoral (Alpes de Haute Provence) - 03 juil 1996
  - **ID 534** - Arrêté préfectoral (Alpes-de-Haute-Provence) - 29 déc 1997
- Conclusion : pas de confirmation officielle positive supplémentaire dans cette passe.

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

### Lot géographique examiné : Isère
- Site officiel accessible : `https://www.isere.gouv.fr/`
- Recherche interne testée : `https://www.isere.gouv.fr/contenu/action?SearchText=canyoning`
- Résultat observé : **8 résultats**, dont plusieurs PDF de **Recueil des actes administratifs (RAA)** mentionnant le mot `canyoning`.
- Interprétation : il existe de la matière officielle exploitable dans les publications Isère, mais une lecture PDF ciblée est nécessaire pour déterminer si ces résultats correspondent à de véritables réglementations canyoning encore en vigueur.
- Conclusion : **lot prometteur pour détection de textes manquants ou mis à jour**, mais pas de confirmation ferme ajoutée dans cette passe.

### Lot géographique examiné : Ain
- Site officiel accessible : `https://www.ain.gouv.fr/`
- Recherche interne testée : `https://www.ain.gouv.fr/contenu/action?SearchText=canyoning`
- Résultat observé : **1 résultat**, lié aux *dispositions spécifiques ORSEC secours en montagne* ; le mot canyoning n'y apparaît que comme activité mentionnée, sans réglementation canyoning locale clairement exploitable.
- Conclusion : aucune confirmation supplémentaire ni candidat suffisamment solide ajouté dans cette passe.

### Lot géographique examiné : Corse-du-Sud / Haute-Corse
- Sites officiels accessibles :
  - `https://www.corse-du-sud.gouv.fr/`
  - `https://www.haute-corse.gouv.fr/`
- Recherches internes testées sur `canyoning` : **0 résultat** sur les deux portails.
- Conclusion : aucune confirmation supplémentaire ni nouveau candidat solide identifié dans cette passe, malgré l'importance du lot corse dans le dataset.

## Prochaine priorité recommandée
1. **Isère** : inspection manuelle des RAA 2015/2018/2022/2023 mentionnant `canyoning`
2. **Var** : `ID 579 - Arrêté préfectoral (Var) - 13 mai 2025` (84 canyons liés)
3. **Alpes-Maritimes** : `ID 349`, `ID 153`, `ID 194`
