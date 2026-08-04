# Refonte UI/UX 2026 - Plan d'execution front/UI

## 1. Objectif

Ce document decrit une refonte graphique et UX complete de l'application Android Descente-Canyon, avec un niveau de detail suffisant pour lancer le travail des ingenieurs front/UI.

La refonte doit transformer l'application actuelle, fonctionnelle mais tres standard Material, en une experience moderne, technique et terrain, adaptee a la preparation et a l'usage outdoor.

Contraintes validees :

- Style cible : technique / terrain.
- Identite visuelle : autonome, non strictement calquee sur descente-canyon.com.
- Dark mode : experience de reference.
- Light mode : disponible et soigne, pas un simple fallback.
- Photos : utilisables comme matiere visuelle centrale, avec credit auteur visible.
- Priorite device : telephone.
- Tablette/foldable : doivent rester utilisables avec des layouts non etires.
- Stack : Android natif, Kotlin, Jetpack Compose Material 3, MapLibre, Coil.

## 2. Diagnostic de l'existant

### 2.1 Stack UI actuelle

L'application est structuree autour de Jetpack Compose Material 3.

Fichiers structurants :

- `app/src/main/java/fr/descentecanyon/app/ui/theme/Theme.kt`
- `app/src/main/java/fr/descentecanyon/app/ui/theme/Color.kt`
- `app/src/main/java/fr/descentecanyon/app/ui/theme/Type.kt`
- `app/src/main/java/fr/descentecanyon/app/ui/MainActivity.kt`
- `app/src/main/java/fr/descentecanyon/app/ui/navigation/AppNavHost.kt`
- `app/src/main/java/fr/descentecanyon/app/ui/components/CanyonSummaryCard.kt`
- `app/src/main/java/fr/descentecanyon/app/ui/components/CompactAppBar.kt`

Ecrans principaux :

- Accueil : `HomeScreen.kt`
- Recherche : `SearchScreen.kt`, `SearchFiltersSheet.kt`
- Carte : `MapScreen.kt`, `MapLibreView.kt`
- Fiche canyon : `CanyonDetailScreen.kt`
- Photos : `PhotoGalleryScreen.kt`
- Favoris : `FavoritesScreen.kt`
- Notifications : `NotificationCenterScreen.kt`
- Formulaire debit : `DebitFormScreen.kt`
- Formulaire interet : `InterestRatingFormScreen.kt`

### 2.2 Forces actuelles

- Architecture Compose claire.
- Utilisation de Material 3 deja presente.
- Navigation type-safe avec routes `Screen`.
- Plusieurs composants partages existent deja.
- App fonctionnelle en dark et light mode.
- MapLibre deja integre.
- Galerie photo immersive deja assez avancee.
- Tests ViewModel et quelques tests UI presents.

### 2.3 Limites actuelles

- UI tres generique : listes, cartes, badges Material standard.
- Peu de systeme design centralise au-dela des couleurs et typos.
- `CanyonDetailScreen.kt` contient beaucoup de logique UI dans un seul fichier tres long.
- Les cartes affichent beaucoup d'information mais peu de hierarchie decisionnelle.
- Les captures tablette montrent des listes etirees horizontalement.
- La carte est traitee comme un bloc dans une page, pas comme une experience centrale.
- Les formulaires sont longs, lineaires et peu guides.
- Les photos ne structurent pas encore l'identite de l'app.
- Les etats offline, risque, praticabilite et fraicheur des donnees sont fonctionnels mais pas assez visibles.

## 3. Direction artistique

### 3.1 Nom de direction

Direction recommandee : **Topo Terrain 2026**.

Cette direction doit evoquer :

- Carnet de topo numerique.
- Instrument de terrain.
- Application fiable avant une sortie canyon.
- Lecture rapide des risques.
- Esthetique outdoor technique, moderne, dense mais lisible.

### 3.2 Principes graphiques

- Dark-first : le dark mode est l'experience premium et la reference de design.
- Light mode mineral : le mode clair doit rester elegant, pas blanc brut.
- Surfaces profondes : utiliser des couches de surfaces, pas seulement un background plat.
- Photos comme matiere : hero, transitions, overlays et credits auteur.
- Cartographie comme langage : courbes topo, grilles, coordonnees, lignes fines.
- Couleurs semantiques : debit, risque, offline, meteo et praticabilite doivent avoir des roles stables.
- Densite controlee : l'application contient beaucoup d'information, mais les ecrans doivent respirer.
- Lisibilite exterieure : fort contraste, texte robuste, actions claires.

### 3.3 Ambiance visuelle

Dark mode :

- Background principal : graphite bleute tres sombre.
- Surfaces : ardoise, basalt, bleu nuit.
- Accent principal : cyan glacier.
- Accent secondaire : vert eau / foret.
- Accent terrain : orange roche.
- Risque : rouge profond.
- Warning : ambre.
- Offline : gris froid.

Light mode :

- Background : gris mineral froid, pas blanc pur.
- Surfaces : ivoire froid ou gris tres clair.
- Accents identiques mais legerement assombris.
- Bordures fines et ombres legeres.
- Photos et cartes doivent apporter de la profondeur.

### 3.4 A eviter

- UI trop lifestyle / aventure grand public.
- Surfaces glassmorphism excessives qui nuisent a la lisibilite.
- Couleurs pastel faibles en contraste.
- Badges uniquement colores sans texte.
- Animations lentes ou decoratives.
- Layouts tablette qui etirent simplement les listes.

## 4. Architecture UI cible

### 4.1 Nouveau package design system

Creer un package dedie :

`app/src/main/java/fr/descentecanyon/app/ui/design/`

Fichiers recommandes :

- `DcTheme.kt`
- `DcColors.kt`
- `DcTypography.kt`
- `DcShapes.kt`
- `DcSpacing.kt`
- `DcElevation.kt`
- `DcMotion.kt`
- `DcWindowSize.kt`
- `DcComponents.kt`

Objectif : isoler les decisions graphiques de l'application pour eviter que chaque ecran redefinisse ses propres paddings, couleurs, shapes et elevations.

### 4.2 Strategie de migration

Ne pas tout reecrire en une fois.

Ordre recommande :

1. Creer les tokens et composants de base.
2. Migrer les composants partages (`CompactAppBar`, `CanyonSummaryCard`, `AppFloatingActionButton`).
3. Migrer les ecrans prioritaires : fiche canyon, recherche, carte.
4. Migrer les ecrans secondaires.
5. Supprimer progressivement les styles locaux dupliques.

### 4.3 Regles d'implementation Compose

- Conserver les `testTag` existants.
- Garder les signatures d'ecrans stables autant que possible.
- Extraire les gros blocs UI en composants prives ou fichiers dedies.
- Eviter les composants trop abstraits tant qu'ils ne sont pas reutilises.
- Ne pas ajouter de backward compatibility inutile.
- Prioriser les petites PRs verticales, ecran par ecran.
- Ajouter des previews Compose pour les nouveaux composants clefs.

## 5. Design system detaille

### 5.1 Couleurs

Creer `DcColors.kt` avec des palettes et roles semantiques.

Roles globaux :

- `backgroundBase`
- `backgroundElevated`
- `surfaceBase`
- `surfaceRaised`
- `surfaceOverlay`
- `surfacePhotoScrim`
- `textPrimary`
- `textSecondary`
- `textMuted`
- `borderSubtle`
- `borderStrong`
- `primaryAction`
- `primaryActionContent`
- `secondaryAction`

Roles terrain :

- `water`
- `waterDeep`
- `waterMist`
- `rock`
- `rockLight`
- `forest`
- `snow`
- `topoLine`
- `mapAccent`

Roles securite et conditions :

- `riskLow`
- `riskMedium`
- `riskHigh`
- `riskExtreme`
- `warning`
- `offline`
- `stale`

Roles debit :

- `flowDry`
- `flowTrickle`
- `flowGood`
- `flowHigh`
- `flowVeryHigh`
- `flowFlood`
- `flowUnknown`

Important : les couleurs de debit doivent rester reconnaissables par les pratiquants, mais peuvent etre modernisees. Les labels texte restent obligatoires.

### 5.2 Theme Compose

Mettre a jour `DescenteCanyonTheme` ou creer `DcTheme`.

Decision recommandee :

- Desactiver `dynamicColor` par defaut pour conserver l'identite autonome.
- Garder une option technique pour le reactiver uniquement si explicitement voulu.
- Aligner `MaterialTheme.colorScheme` sur les roles principaux.
- Exposer des tokens supplementaires via `CompositionLocal` si necessaire.

Exemple de structure :

```kotlin
@Composable
fun DcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dcColors = if (darkTheme) DcDarkColors else DcLightColors
    CompositionLocalProvider(LocalDcColors provides dcColors) {
        MaterialTheme(
            colorScheme = dcColors.toMaterialColorScheme(),
            typography = DcTypography,
            shapes = DcShapes,
            content = content,
        )
    }
}
```

### 5.3 Typographie

Objectif : plus technique, plus lisible, moins generique.

Recommandation :

- Font principale : `Inter`, `Roboto Flex` ou `Sora`.
- Font optionnelle pour metriques : une mono lisible, par exemple `JetBrains Mono` ou `Roboto Mono`.

Hierarchie :

- `displayTerrain` : grand hero, nom canyon.
- `headlineScreen` : titre d'ecran.
- `titleCard` : titre de carte.
- `titleSection` : section.
- `metricValue` : valeurs techniques, debit, altitude, temps.
- `metricLabel` : libelles.
- `bodyTerrain` : contenu topo long.
- `captionCredit` : auteur photo, sources.

Regles :

- Les valeurs techniques doivent etre plus lisibles que les libelles.
- Les longs textes topo doivent avoir un line-height confortable.
- Les labels de badges doivent rester lisibles a petite taille.

### 5.4 Shapes

Creer `DcShapes.kt`.

Recommandation :

- `xs`: 6dp
- `sm`: 10dp
- `md`: 16dp
- `lg`: 22dp
- `xl`: 28dp
- `xxl`: 36dp
- `pill`: 999dp

Usage :

- Cards standards : `lg`.
- Hero cards : `xl` ou `xxl`.
- Badges : `pill`.
- Champs recherche : `xl`.
- Bottom sheets : top corners `xxl`.

### 5.5 Spacing

Creer `DcSpacing.kt`.

Valeurs :

- `xxs`: 2dp
- `xs`: 4dp
- `sm`: 8dp
- `md`: 12dp
- `lg`: 16dp
- `xl`: 20dp
- `xxl`: 24dp
- `section`: 32dp
- `screenHorizontal`: 16dp phone, 24dp tablet.

Regles :

- Tous les ecrans phone doivent utiliser `screenHorizontal`.
- Les cartes internes utilisent `lg` ou `xl`.
- Les sections principales utilisent `section`.

### 5.6 Elevation et bordures

Objectif : eviter la surutilisation d'ombres Material classiques.

Types :

- Flat terrain card : elevation 0, border subtile.
- Raised card : elevation 1-2, border subtile.
- Floating action : elevation 4-8.
- Photo overlay : pas d'elevation, scrim gradient.

Regle dark mode : preferer bordures et surfaces tonales aux grosses ombres.

### 5.7 Motion

Creer `DcMotion.kt`.

Animations recommandees :

- Transition d'ecran courte : 180-240ms.
- Expansion accordions : 180ms.
- Bottom sheet : comportement Material standard.
- Cartes : apparition subtile, pas de delay excessif.
- FAB : scale/alpha leger.
- Map overlays : fade/slide.

Ne pas animer les listes longues element par element par defaut.

## 6. Composants partages a creer ou refondre

### 6.1 `DcScaffold`

Responsabilites :

- Appliquer le background global.
- Gerer safe areas.
- Gerer bottom nav / navigation rail selon taille.
- Fournir content padding coherent.

### 6.2 `DcTopBar`

Remplacer progressivement `CompactAppBar`.

Variantes :

- `default` : ecrans standards.
- `transparentPhoto` : hero photo.
- `compact` : formulaires.
- `mapOverlay` : carte.

Acceptance criteria :

- Support navigation icon.
- Support title long avec ellipsis.
- Support actions multiples.
- Contraste correct sur photo et surface.

### 6.3 `DcCard`

Carte standard terrain.

Props recommandees :

- `variant`: `Surface`, `Elevated`, `Photo`, `Warning`, `Condition`.
- `onClick` optionnel.
- `contentPadding` optionnel.

### 6.4 `DcMetricTile`

Pour les donnees canyon : altitude, denivele, longueur, cascade max, corde, temps.

Elements :

- Label.
- Valeur.
- Unite optionnelle.
- Icone optionnelle.
- Etat optionnel : normal, warning, missing.

### 6.5 `DcFlowBadge`

Remplacer ou envelopper `DebitBadge`.

Contraintes :

- Couleur + texte.
- Support labels courts et longs.
- Support niveau inconnu.
- Contraste AA minimum.

### 6.6 `DcRiskBadge`

Pour EDF, meteo, prediction, crue, reglementation.

Niveaux :

- `Info`
- `Low`
- `Medium`
- `High`
- `Extreme`
- `Unknown`

### 6.7 `DcSectionHeader`

Section avec :

- Titre.
- Sous-titre optionnel.
- Action optionnelle.
- Icone optionnelle.

### 6.8 `DcOutdoorActionCard`

Pour actions rapides : recherche, carte, offline, favoris, signalement.

Doit remplacer les action cards generiques de l'accueil.

### 6.9 `DcEmptyState`

Etats vides coherents pour :

- Aucun favori.
- Aucune photo.
- Aucun debit.
- Resultats recherche vides.
- Offline sans cache.

### 6.10 `DcLoadingSkeleton`

Skeletons pour :

- Fiche canyon.
- Resultats recherche.
- Feed accueil.
- Carte visible list.

## 7. Layout adaptatif

### 7.1 Breakpoints

Creer `DcWindowSize.kt`.

Proposition :

- Compact : largeur < 600dp.
- Medium : 600dp a 839dp.
- Expanded : >= 840dp.

### 7.2 Telephone

- Bottom navigation.
- Full width content.
- Hero compact.
- Bottom sheets pour filtres et carte.
- FAB ou sticky action selon contexte.

### 7.3 Tablette/foldable

- Navigation rail pour ecrans top-level.
- Largeur max pour contenus texte : 760dp a 900dp.
- Recherche : liste + carte ou liste + preview.
- Carte : carte + panneau lateral.
- Fiche canyon : contenu centre avec colonne conditions possible.

### 7.4 A corriger explicitement

Les captures tablette actuelles montrent des cartes tres etirees horizontalement. Il faut introduire :

- `Modifier.widthIn(max = ...)` sur les contenus principaux.
- Layouts a deux colonnes quand utile.
- Padding lateral augmente.
- Navigation rail au lieu de bottom nav si largeur suffisante.

## 8. Navigation

### 8.1 Bottom navigation

Conserver les items :

- Accueil.
- Rechercher.
- Carte.
- Favoris.

Evolution visuelle :

- Fond sombre semi-elevated.
- Indicateur plus technique, moins Material par defaut.
- Icnes plus coherentes si possible.
- Labels visibles sur phone.

### 8.2 Navigation rail

Pour medium/expanded :

- Meme items.
- Afficher labels si espace suffisant.
- Garder actions secondaires dans top bar.

### 8.3 Transitions

Actuellement beaucoup de transitions sont `None`.

Ajouter :

- Crossfade court pour top-level.
- Slide horizontal court pour detail.
- Shared visual continuity entre carte/liste et fiche si raisonnable.

Ne pas bloquer la phase 1 sur les transitions.

## 9. Ecran Accueil

### 9.1 Probleme actuel

L'accueil est surtout une liste de derniers debits avec une recherche rapide. Il ne donne pas assez l'impression d'un tableau de bord terrain.

Fichier :

- `HomeScreen.kt`

### 9.2 Objectif UX

En arrivant sur l'accueil, l'utilisateur doit pouvoir :

- Chercher un canyon rapidement.
- Voir si les donnees recentes sont disponibles.
- Acceder a la carte.
- Comprendre les derniers signaux terrain.
- Retrouver ses suivis/favoris/offline.

### 9.3 Structure cible phone

Ordre vertical recommande :

1. `HomeTerrainHero`
2. `HomeQuickActions`
3. `HomeConditionsFeedHeader`
4. Filtres rapides zone.
5. Feed debits ou forum.
6. Credits/source compact.

### 9.4 `HomeTerrainHero`

Contenu :

- Titre court : `Descente-Canyon` ou une baseline terrain.
- Etat : online/offline/stale.
- Champ ou carte de recherche dominante.
- CTA secondaire : `Autour de moi` ou `Ouvrir la carte`.

Visual :

- Fond gradient water/rock.
- Courbes topo discretes en overlay.
- Icone boussole ou goutte.

### 9.5 `HomeQuickActions`

Actions recommandees :

- Rechercher.
- Carte.
- Favoris.
- Offline / telechargements.

Phone : grid 2x2.

Tablet : row ou grid avec max width.

### 9.6 Feed debits

Remplacer `DebitCard` par une carte plus terrain.

Contenu :

- Canyon nom.
- Date.
- Auteur si disponible.
- Niveau debit avec badge.
- Couleur laterale ou top strip.
- Commentaire tronque si utile.
- Indication externe si non embarque.

Acceptance criteria :

- Un debit `CRUE` doit etre immediatement visible.
- Les couleurs ne doivent pas etre le seul indicateur.
- Le feed reste dense et scrollable.

### 9.7 Feed forum

Moderniser `ForumTopicCard`.

Contenu :

- Titre sujet.
- Rubrique.
- Nombre de reponses.
- Auteur/date dernier message.
- Etat suivi rubrique/sujet.
- Action overflow conservee.

### 9.8 Etats offline/stale

Refondre `HomeFeedBanner` et `HomeStatusCard` avec `DcRiskBadge` ou `DcStatusCard`.

Etat offline doit repondre a :

- Est-ce que je vois du cache ?
- Quelle est la derniere synchro ?
- Que puis-je faire maintenant ?

## 10. Ecran Recherche

### 10.1 Probleme actuel

Recherche fonctionnelle mais visuellement standard. Les controles occupent beaucoup d'espace et manquent de fluidite terrain.

Fichiers :

- `SearchScreen.kt`
- `SearchFiltersSheet.kt`
- `CanyonSummaryCard.kt`

### 10.2 Objectif UX

Permettre une recherche rapide et experte : nom, zone, cotation, interet, distance, contraintes.

### 10.3 Structure cible phone

1. Search field sticky ou en haut.
2. Chips de filtres actifs.
3. Ligne actions : filtres, tri, vue carte/liste.
4. Result count.
5. Liste resultats ou carte.
6. Bottom sheet pour filtres avances.

### 10.4 Champ de recherche

Doit etre plus premium :

- Shape large.
- Icone recherche.
- Clear visible.
- Etat loading discret.
- Placeholder oriente terrain.

### 10.5 Filtres rapides

Ajouter des chips visibles pour :

- Pays.
- Subdivision.
- Favoris.
- Distance.
- Cotation.
- Reglemente.
- Navette.

Les filtres avances restent dans bottom sheet.

### 10.6 Bottom sheet filtres

Refondre `SearchFiltersSheet` :

- Header sticky : titre, reset, close.
- Sections visuelles avec `DcSectionHeader`.
- Filtres cotation sous forme de range chips ou controls plus compacts.
- Numeric fields en cartes compactes.
- CTA sticky bottom : `Voir les resultats`.

### 10.7 Result cards

Refondre `CanyonSummaryCard`.

Contenu cible :

- Nom.
- Pays/subdivision.
- Cotation badge.
- Interet.
- Dernier debit si present.
- Offline indicator.
- Distance si presente.
- Interdit si applicable.

Variantes :

- `compact` pour listes longues.
- `rich` pour carte/bottom sheet.
- `mapSheet` pour selection marker.

### 10.8 Mode carte dans recherche

Le mode carte doit utiliser :

- Carte quasi pleine hauteur disponible.
- Sheet selection canyon.
- FAB ou segmented control pour revenir liste.

### 10.9 Etats vides

Prevoir :

- Catalogue en preparation.
- Recherche trop large.
- Aucun resultat.
- Aucun resultat avec coordonnees pour carte.
- Permission localisation refusee.

## 11. Ecran Carte

### 11.1 Probleme actuel

La carte est integree dans une page avec hero et liste. Sur phone, elle devrait etre plus centrale.

Fichiers :

- `MapScreen.kt`
- `MapLibreView.kt`
- `MapConfig.kt`
- assets `app/src/main/assets/map/*.json`

### 11.2 Objectif UX

Faire de la carte un outil terrain principal.

L'utilisateur doit pouvoir :

- Voir les canyons proches ou visibles.
- Comprendre les clusters.
- Ouvrir une fiche rapidement.
- Recentrer sur sa position.
- Filtrer sans quitter la carte.

### 11.3 Structure cible phone

- Map en fond principal.
- Top overlay : titre court, count visible, filtres.
- Floating action : autour de moi.
- Bottom sheet : canyons visibles.
- Sheet collapsed par defaut, expandable.

### 11.4 Structure tablette

- Navigation rail.
- Carte a gauche ou plein espace.
- Panneau lateral droit pour canyons visibles.
- Selection canyon dans panneau, pas forcement modal.

### 11.5 Map style

Revoir deux styles :

- `opentopomap_style.json` pour carte principale.
- `osm_light_style.json` pour recherche.

Objectifs :

- Harmoniser les couleurs avec le theme.
- Ameliorer lisibilite des clusters.
- Reduire saturation si fond trop charge.
- Conserver reperes topographiques.

### 11.6 Clusters et markers

Moderniser :

- Clusters circulaires ou capsules terrain.
- Stroke clair en dark mode.
- Couleurs semantiques : densite, interet ou zone.
- Marker utilisateur plus visible.

### 11.7 Selection canyon

`SelectedCanyonSheetContent` doit utiliser la nouvelle `CanyonSummaryCard` variante `mapSheet`.

Ajouter :

- CTA `Ouvrir la fiche`.
- Indices principaux : cotation, interet, dernier debit.

## 12. Fiche Canyon

### 12.1 Probleme actuel

La fiche canyon est riche mais tres tabulaire. Elle ne met pas assez en avant les informations de decision.

Fichier principal :

- `CanyonDetailScreen.kt`

Fichiers associes :

- `CanyonWeatherCard.kt`
- `CanyonEdfStatusCard.kt`
- `CanyonDebitPredictionCard.kt`
- `CanyonDetailDebitComponents.kt`
- `CanyonPhotoImage.kt`

### 12.2 Objectif UX

En moins de 5 secondes, l'utilisateur doit comprendre :

- Quel canyon il regarde.
- Ou il se situe.
- Sa difficulte.
- Son interet.
- Si les conditions semblent bonnes ou risquées.
- Comment acceder a la carte, aux debits, au topo et aux photos.

### 12.3 Decoupage technique recommande

Extraire depuis `CanyonDetailScreen.kt` :

- `CanyonDetailHeader.kt`
- `CanyonHero.kt`
- `CanyonConditionCockpit.kt`
- `CanyonMetricGrid.kt`
- `CanyonTopoSections.kt`
- `CanyonPhotoSection.kt`
- `CanyonDebitSection.kt`
- `CanyonRegulationSection.kt`
- `CanyonBibliographySection.kt`
- `CanyonMapActions.kt`

### 12.4 Hero canyon

Composant : `CanyonHero`.

Sources visuelles :

- Priorite 1 : photo canyon disponible.
- Priorite 2 : thumbnail photo.
- Priorite 3 : extrait carte/topo ou gradient terrain.

Contenu :

- Nom canyon.
- Commune / pays.
- Cotation.
- Interet.
- Badge interdit si applicable.
- Credit photo : auteur, si photo utilisee.
- Action favorite.
- Action notifications.

Regles :

- Scrim gradient obligatoire sur photo.
- Credit auteur discret mais visible.
- Le titre doit rester lisible sur toutes les photos.
- Si pas de photo, hero doit quand meme etre premium.

### 12.5 Cockpit conditions

Composant : `CanyonConditionCockpit`.

Afficher sous le hero :

- Dernier debit.
- Prediction aujourd'hui/demain.
- Meteo pluie 72h.
- Statut EDF si disponible.
- Fraicheur des donnees.

Format :

- 2 colonnes sur phone si possible.
- Grille plus large sur tablette.
- Chaque tuile a un niveau : normal, info, warning, high risk, unknown.

Acceptance criteria :

- Les conditions critiques ressortent avant les donnees techniques.
- L'utilisateur ne doit pas ouvrir trois accordions pour voir les signaux importants.

### 12.6 Resume technique

Remplacer les tableaux actuels par `CanyonMetricGrid`.

Groupes :

- Parcours : altitude, denivele, longueur, cascade max, corde.
- Temps : approche, descente, retour.
- Localisation : communes, massif, bassin, surface bassin versant, cours d'eau.

Phone :

- Tuiles 2 colonnes pour les metriques courtes.
- Liste compacte pour localisation longue.

Tablet :

- 3 ou 4 colonnes.

### 12.7 Actions principales

Actions :

- Voir la carte.
- Signaler un debit.
- Noter l'interet.
- Telecharger offline si fonctionnalite exposee.

Placement :

- Phone : action bar sticky ou FAB group selon faisabilite.
- Fiche actuelle a deja un FAB carte ; le conserver au debut puis evoluer vers une action bar.

### 12.8 Tabs

Tabs actuels : Topo, Photos, Debits.

Proposition :

- `Topo`
- `Conditions`
- `Photos`
- `Debits`
- `Carte` si pertinent.

Approche pragmatique :

- Phase 1 : conserver 3 tabs pour limiter le risque.
- Phase 2 : evaluer ajout `Conditions` ou `Carte` si la fiche devient trop dense.

### 12.9 Topo sections

Refondre `CollapsibleSection`.

Ameliorations :

- Icone par type : acces, approche, descente, retour, navette, reglementation.
- Apercu de 2-3 lignes avant expansion si utile.
- Typographie long texte amelioree.
- Liens mieux visibles.
- Etat expanded memorise par section et canyon.

### 12.10 Photos dans fiche

Dans tab photos :

- Grille ou grandes cards selon nombre.
- Auteur visible sur chaque photo si disponible.
- Etat offline/download clair.
- CTA galerie.

Dans hero :

- Une seule photo principale.
- Credit auteur obligatoire.

### 12.11 Debits dans fiche

Moderniser la liste debits :

- Timeline ou cards compactes.
- Niveau debit tres visible.
- Date/auteur/commentaire.
- Highlight du dernier debit.
- Etat aucun debit avec action `Signaler un debit`.

### 12.12 Reglementation

Les regulations doivent etre traitees comme securite, pas simple texte.

Ameliorations :

- Badge statut.
- Carte warning si interdit ou restriction.
- Liens source clairs.
- Sections attachments propres.

## 13. Meteo, EDF et predictions

### 13.1 Probleme actuel

Les cards existent mais sont surtout des accordions textes.

Fichiers :

- `CanyonWeatherCard.kt`
- `CanyonEdfStatusCard.kt`
- `CanyonDebitPredictionCard.kt`

### 13.2 Objectif

Transformer ces donnees en indicateurs decisionnels.

### 13.3 Weather card

Version collapsed :

- Pluie passee 72h.
- Pluie prevue 48h.
- Probabilite max.
- Etat risque calcule simple si possible cote UI.

Version expanded :

- Tuiles metriques.
- Source coordonnee.
- Derniere mise a jour.

### 13.4 EDF card

Version collapsed :

- Praticable / non praticable / inconnu.
- Niveau courant si disponible.

Version expanded :

- Seuils.
- Date sample.
- Evenement en cours.
- Lien source.

### 13.5 Prediction card

Version collapsed :

- Aujourd'hui.
- Demain.
- Confiance.

Version expanded :

- Gauge modernisee.
- Legende accessible.
- Explication via CTA info.

## 14. Photos et galerie

### 14.1 Objectif

Faire des photos un element d'identite, sans nuire aux donnees critiques.

Fichier :

- `PhotoGalleryScreen.kt`

### 14.2 Fiche canyon

- Hero photo.
- Credit auteur visible.
- Scrim lisible.
- Fallback premium si pas de photo.

### 14.3 Galerie

Conserver :

- Pager horizontal.
- Zoom / pan.
- Tap overlay.
- Telechargement.

Ameliorer :

- Header overlay plus compact.
- Bottom metadata plus lisible.
- Credit auteur systematique.
- Indicateur offline/download clair.
- Animation d'entree depuis la photo si faisable.

### 14.4 Accessibility

- Description image si disponible.
- Boutons avec content description.
- Overlay lisible avec contraste suffisant.

## 15. Favoris

### 15.1 Probleme actuel

Ecran simple liste + vide.

Fichier :

- `FavoritesScreen.kt`

### 15.2 Objectif

Evoluer vers un espace `Mes canyons`.

Contenu futur possible :

- Favoris.
- Offline.
- Suivis notifications.
- Derniers debits sur favoris.

Phase 1 :

- Refondre header.
- Moderniser empty state.
- Utiliser nouvelle `CanyonSummaryCard`.
- Conserver swipe-to-dismiss.

Phase 2 :

- Ajouter sections ou filtres : tous, offline, suivis.

## 16. Notifications

### 16.1 Fichier

- `NotificationCenterScreen.kt`

### 16.2 Objectif

Rendre le centre de notifications plus orientee surveillance terrain.

Sections :

- Activite recente.
- Canyons suivis.
- Forum suivi.
- Permission notifications.

Ameliorations :

- Cards d'activite type timeline.
- Badges debit/forum.
- Empty states coherents.
- Actions retirer/ouvir plus compactes.

## 17. Formulaire debit

### 17.1 Probleme actuel

Formulaire long, lineaire, radio rows standards.

Fichier :

- `DebitFormScreen.kt`

### 17.2 Objectif UX

Permettre un signalement rapide sur le terrain.

L'utilisateur doit comprendre :

- Ce qui est obligatoire.
- S'il envoie en ligne ou met en file offline.
- Quel niveau de debit il selectionne.

### 17.3 Structure cible

1. Header compact canyon/action.
2. Etat connexion/login/offline.
3. Date observation.
4. Type observation.
5. Niveau debit en cartes colorees.
6. Temperature eau/air en chips ou segmented cards.
7. Commentaire.
8. Submit sticky.

### 17.4 `FormChoiceSection`

Refondre :

- Ajouter variant compact/chip/card.
- Pour debit : grandes cartes colorees avec description.
- Pour temperature : chips plus compactes.

### 17.5 Submit

Le bouton submit doit etre sticky en bas sur phone si le formulaire est long.

Etats :

- Enabled.
- Disabled.
- Submitting.
- Queued offline.
- Error.

## 18. Formulaire interet

### 18.1 Fichier

- `InterestRatingFormScreen.kt`

### 18.2 Objectif UX

Rendre la notation plus tangible.

Ameliorations :

- Hero de note actuelle.
- Slider plus visuel.
- Etoiles synchronisees avec slider.
- Guide compact et expandable.
- Submit sticky.

### 18.3 Guide interet

Le guide actuel peut rester, mais doit devenir :

- Plus compact par defaut.
- Lisible en cartes.
- Avec niveaux 0 a 4 visibles.

## 19. Authentification

### 19.1 Fichier

- `LoginDialog.kt`

### 19.2 Objectif

Moderniser sans complexifier.

Ameliorations :

- Dialog plus visuel.
- Etat connecte clair.
- Erreurs mieux placees.
- Bouton principal rempli, secondaire text.
- Champs avec icones si utile.

## 20. Offline et fraicheur des donnees

### 20.1 Objectif

Faire de l'offline une fonctionnalite terrain visible, pas un detail technique.

Indicateurs :

- Fiche disponible offline.
- Photos offline.
- Carte offline.
- Derniere synchro.
- Donnees stale.
- Actions de telechargement/suppression.

### 20.2 UX

Sur fiche canyon :

- Badge offline dans hero.
- Section ou action `Disponible terrain`.
- Etat des photos/cartes si disponible.

Sur accueil :

- Banner offline/stale.
- Derniere synchro.

Sur recherche/favoris :

- Icone offline sur cards.

## 21. Accessibilite

### 21.1 Contraste

Exigences :

- Texte normal : ratio WCAG AA minimum.
- Badges debit : texte lisible en dark et light.
- Texte sur photo : scrim obligatoire.

### 21.2 Taille et touch targets

- Touch target min : 48dp.
- Ne pas reduire les icones critiques sous 24dp.
- Text scaling doit etre teste.

### 21.3 Couleurs

Ne jamais utiliser la couleur seule pour :

- Debit.
- Risque.
- Praticabilite.
- Offline.

Toujours ajouter label, icon ou pattern.

### 21.4 Screen readers

- Content descriptions sur actions.
- Images photos : utiliser description si disponible.
- Icons decoratives : contentDescription null.
- Badges : texte comprehensible.

## 22. Tests et validation

### 22.1 Tests existants a proteger

Preserver les `TestTags` existants :

- `TestTags.searchQueryField`
- `TestTags.homeQuickSearch`
- `TestTags.canyonCard(id)`
- `TestTags.detailReportDebitButton`
- `TestTags.detailRateInterestButton`
- `TestTags.detailDebitNotificationButton`
- `TestTags.detailFavoriteButton`
- `TestTags.favoritesList`
- `TestTags.debitSubmitButton`
- `TestTags.interestRatingSubmitButton`

### 22.2 Tests Compose recommandes

Ajouter ou adapter :

- `CanyonSummaryCard` affiche cotation/interet/debit/offline.
- `CanyonConditionCockpit` affiche etats loading/error/normal.
- `HomeScreen` affiche hero + recherche rapide.
- `SearchScreen` conserve recherche et filtres.
- `DebitFormScreen` submit reste accessible.

### 22.3 Validation visuelle

Creer captures :

- Phone dark : accueil, recherche, carte, fiche, formulaire.
- Phone light : accueil, recherche, fiche.
- Tablet light/dark : accueil, recherche, carte, fiche.

### 22.4 Verification manuelle

Checklist :

- Dark mode complet.
- Light mode complet.
- Rotation portrait/paysage acceptable.
- Texte long canyon lisible.
- Photos sans auteur : fallback propre.
- Fiche sans photo : hero fallback propre.
- Offline/stale visibles.
- Map markers lisibles.
- Keyboard sur recherche ne masque pas les resultats critiques.

## 23. Performance

### 23.1 Risques

- Hero photo sur fiche peut ajouter du cout.
- Map full-screen avec overlays peut augmenter la charge.
- Fiche canyon deja longue, attention aux recompositions.

### 23.2 Regles

- Utiliser Coil avec thumbnails si disponibles.
- Eviter les transformations photo lourdes.
- Garder LazyColumn pour fiche.
- Eviter nested scroll complexe sauf necessaire.
- Ne pas charger toutes les images full-size dans une liste.
- Utiliser keys stables dans les LazyColumn.

### 23.3 Recomposition

- Extraire composants stables.
- Passer uniquement les props necessaires.
- Eviter allocations inutiles dans composables tres frequents.
- `remember` pour calculs derives locaux non triviaux.

## 24. Roadmap detaillee

### Phase 0 - Preparation

Objectif : securiser le chantier.

Taches :

- Creer branche de refonte.
- Ajouter captures de reference actuelles.
- Identifier tests UI critiques.
- Confirmer choix font.
- Confirmer palette finale.
- Lister ecrans P0/P1/P2.

Livrables :

- Decision palette.
- Decision typographie.
- Liste des screenshots baseline.

### Phase 1 - Design system

Objectif : poser les fondations.

Taches :

- Creer package `ui/design`.
- Ajouter tokens couleurs dark/light.
- Ajouter spacing, shapes, elevations.
- Ajouter typographie.
- Brancher theme dans `MainActivity`.
- Migrer `CompactAppBar` vers `DcTopBar` ou wrapper.
- Migrer `AppFloatingActionButton`.
- Creer `DcCard`, `DcMetricTile`, `DcFlowBadge`, `DcRiskBadge`.

Acceptance criteria :

- App compile.
- Dark/light affichent palette cible.
- Ecrans existants restent fonctionnels.
- Aucun test existant casse pour cause de `testTag` supprime.

### Phase 2 - Cards et composants transverses

Objectif : moderniser les elements repetes.

Taches :

- Refondre `CanyonSummaryCard`.
- Refondre `SelectedCanyonSheetContent`.
- Refondre badges cotation/debit/interet.
- Creer empty states partages.
- Creer skeletons de base.

Acceptance criteria :

- Recherche, carte, favoris utilisent les nouvelles cards.
- Les cartes restent denses mais plus lisibles.
- Interdit/offline/debit restent visibles.

### Phase 3 - Fiche canyon P0

Objectif : livrer le plus gros gain percu.

Taches :

- Extraire fichiers depuis `CanyonDetailScreen.kt`.
- Creer `CanyonHero` avec photo + credit auteur.
- Creer `CanyonConditionCockpit`.
- Refondre summary stats en metric grid.
- Refondre accordions topo.
- Moderniser photos tab.
- Moderniser debits tab.
- Verifier etats loading/error/offline.

Acceptance criteria :

- L'utilisateur comprend conditions + difficulte sans scroller loin.
- Fiche avec photo et sans photo sont propres.
- Auteur photo visible quand disponible.
- Actions favorite, notification, debit, interet fonctionnent.
- FAB carte ou action carte reste accessible.

### Phase 4 - Recherche P0

Objectif : rendre la recherche plus moderne et efficace.

Taches :

- Refaire header recherche.
- Ajouter chips actifs.
- Refondre controles tri/filtres.
- Refondre bottom sheet filtres.
- Moderniser etats vides/loading.
- Harmoniser mode liste/carte.

Acceptance criteria :

- Recherche nom fonctionne identiquement.
- Filtres existants conserves.
- Tri distance conserve permission location.
- Carte recherche utilisable.

### Phase 5 - Carte P1

Objectif : experience map-first.

Taches :

- Repenser `MapScreen` autour de la carte.
- Ajouter overlays flottants.
- Ajouter bottom sheet canyons visibles.
- Adapter tablette avec panneau lateral.
- Revoir markers/clusters.
- Ajuster style map si necessaire.

Acceptance criteria :

- Carte lisible en dark et light.
- Clusters comprehensibles.
- Selection canyon ouvre sheet puis fiche.
- Autour de moi reste accessible.

### Phase 6 - Accueil P1

Objectif : tableau de bord terrain.

Taches :

- Creer `HomeTerrainHero`.
- Creer quick actions grid.
- Moderniser feed debits/forum.
- Moderniser offline/stale banners.
- Conserver donation/source sous forme moins dominante.

Acceptance criteria :

- Recherche accessible immediatement.
- Derniers debits visibles.
- Forum toujours accessible.
- Offline/stale clairs.

### Phase 7 - Formulaires P2

Objectif : contribution plus rapide.

Taches :

- Refondre formulaire debit.
- Refondre choix debit en cards visuelles.
- Ajouter sticky submit.
- Refondre formulaire interet.
- Moderniser login dialog.

Acceptance criteria :

- Soumission debit online/offline conservee.
- Login requis conserve.
- Formulaires restent testables.

### Phase 8 - Adaptive layouts P2

Objectif : tablette/foldable utilisables.

Taches :

- Ajouter window size class locale.
- Navigation rail medium/expanded.
- Width max sur contenus.
- Carte + panneau lateral.
- Recherche + panneau preview si faisable.

Acceptance criteria :

- Les cartes ne s'etirent plus sur toute la largeur tablette.
- Navigation tablette plus naturelle.
- Phone non degrade.

### Phase 9 - Polish P3

Objectif : finition 2026.

Taches :

- Ajouter transitions courtes.
- Ajouter skeletons.
- Ajouter haptics legers sur actions critiques si souhaite.
- Refaire screenshots Play Store.
- Finaliser accessibilite.

Acceptance criteria :

- Captures Play Store coherentes.
- Dark et light propres.
- Aucun ecran principal ne donne une impression Material generic.

## 25. Decoupage PR recommande

PR 1 : Design tokens et theme.

PR 2 : Composants partages et cards.

PR 3 : Fiche canyon hero + cockpit.

PR 4 : Fiche canyon sections/photos/debits.

PR 5 : Recherche liste + filtres.

PR 6 : Carte map-first.

PR 7 : Accueil dashboard.

PR 8 : Formulaires.

PR 9 : Adaptive/tablette.

PR 10 : Polish, screenshots et accessibilite.

Chaque PR doit :

- Compiler.
- Garder les tests existants verts.
- Inclure captures avant/apres si changement visuel.
- Ne pas melanger refonte visuelle et changement metier.

## 26. Definition of done globale

La refonte est consideree terminee si :

- Tous les ecrans principaux utilisent les tokens du design system.
- Le dark mode est coherent et premium.
- Le light mode est coherent et lisible.
- La fiche canyon priorise les informations de decision.
- La recherche est plus claire et plus rapide visuellement.
- La carte est une experience principale, pas un bloc secondaire.
- Les photos enrichissent la fiche avec credit auteur.
- Les formulaires sont plus guides.
- Les layouts tablette ne sont plus simplement etires.
- Les tests critiques passent.
- Les captures Play Store sont refaites.

## 27. Questions restantes pour l'equipe produit/design

Avant implementation complete, valider :

- Police finale : system font, Inter, Sora, Roboto Flex ou autre.
- Niveau d'usage photo dans le hero : toujours si possible ou optionnel selon qualite.
- Priorite exacte des signaux dans le cockpit : debit, meteo, EDF, prediction.
- Presence d'une action offline visible sur la fiche.
- Nom final de l'identite visuelle interne.
- Niveau d'effort accepte pour refonte MapLibre.

## 28. Synthese executif

La refonte doit partir du design system, puis attaquer la fiche canyon et la recherche. Ces deux ecrans concentrent la valeur utilisateur. L'app doit devenir un outil technique de terrain : rapide, lisible, robuste, sombre par defaut, avec une identite autonome et des photos bien integrees.

Le fil directeur pour toutes les decisions UI est simple : aider le pratiquant a prendre une decision de sortie plus vite, avec plus de confiance.
