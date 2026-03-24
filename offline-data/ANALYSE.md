# Analyse donnees offline Descente-Canyon

## Donnees actuellement utilisees par l'app

### Recherche / listes
- `id`
- `nom`
- `pays`
- `departement`
- `cotation`
- `interet`
- `url`
- `isOffline` (local seulement, a conserver en base locale app)

### Proximite / carte
- `latitude`
- `longitude`
- `representativePoint.type`

### Fiche canyon
- `nom`, `nomComplet`
- `pays`, `commune`, `departement`, `region`, `massif`, `bassin`, `coursEau`
- `cotation`, `interet`, `nbVotes`
- `altitudeDepart`, `denivele`, `longueur`, `cascadeMax`, `cordeMin`
- `tempsApproche`, `tempsDescente`, `tempsRetour`, `navette`
- `accesAval`, `accesAmont`, `approche`, `descente`, `retour`, `engagement`, `periode`
- `geologie`, `historique`, `remarques`
- `geoPoints[]`
- `bibliographie` (topoguides + cartes + ressources web)
- `reglementation`

### Donnees a exclure de l'embarque initial
- photos et miniatures
- debits canyon et derniers debits
- metadonnees auteur/date/avatar des photos et geopoints si elles ne servent pas en UI
- tout ce qui change souvent ou grossit vite

## Structure conseillee

### 1) `index.json`
Fichier leger charge au demarrage pour la recherche et la carte.

Champs recommandes:
- `id`
- `nom`
- `pays`
- `departement`
- `commune`
- `massif`
- `bassin`
- `coursEau`
- `cotation`
- `interet`
- `url`
- `hasSpecificRegulation`
- `representativePoint { type, latitude, longitude, label }`

### 2) `canyons/<id>.json`
Fichier detaille par canyon, charge a l'ouverture de la fiche.

Blocs recommandes:
- `identity`
- `location`
- `rating`
- `metrics`
- `timings`
- `topo`
- `geoPoints`
- `bibliography`
- `reglementation`

## Taille estimee

- `carte.json` du site: environ `470 Ko` pour `3791` canyons
- echantillon detail JSON local: environ `4.1 Ko` brut par canyon
- echantillon detail JSON local gzip: environ `1.46 Ko` par canyon

Projection tres grossiere:
- index global: ~`0.5 Mo` brut
- details texte: ~`15-16 Mo` brut pour toute la base
- details texte compresses: ~`5.5-6 Mo`

Ca reste raisonnable pour un embarque en assets si on sharde les fichiers et qu'on exclut photos/debits.

## Points a valider

- `communes[]` : certains canyons ont plusieurs communes, alors que le modele app actuel n'en garde qu'une
- certaines pages `biblio` classent aussi des liens web dans la section `Carte`; ils seront stockes dans `resources[]`
- `representativePoint.label` : aujourd'hui l'app affiche surtout le type; en offline on peut embarquer le vrai libelle de la carte quand il existe
- les textes de `reglementation` sont souvent partages entre plusieurs canyons, donc a dedupliquer globalement
