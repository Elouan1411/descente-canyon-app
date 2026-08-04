# Plan d'optimisation app 2026

Date d'analyse: 2026-06-16

Perimetre: application Android native `:app`, donnees offline embarquees, modele de prediction debit, carte, startup, architecture mobile, build Play.

Objectifs:

- Reduire la taille de livraison actuelle: `.aab` environ 91.5 MB, `.apk` environ 146 MB.
- Ameliorer la fluidite percue: startup, import initial, recherche, carte, detail canyon, prediction debit.
- Simplifier le code et reduire la dette de maintenabilite.
- Identifier les anciennes fonctionnalites, reliquats et fichiers non utilises.
- Donner un plan actionnable par expert metier, avec validations attendues.

## Resume executif

La taille actuelle est principalement due a deux racines ajoutees en bloc aux assets Android dans `app/build.gradle.kts:62-70`:

| Poste | Taille brute mesuree | Remarque |
| --- | ---: | --- |
| `modele_statistique` | 131.38 MB | Deux modeles ONNX et gros JSON runtime. |
| `offline-data/full/room-import` | 85.27 MB | Principalement `watersheds.json`. |
| `app/src/main/res` | 0.41 MB | Pas prioritaire. |
| `app/src/main/play` | 2.93 MB | Normalement non package dans APK/AAB. |
| `app/src/main/java` | 1.00 MB | Non prioritaire pour la taille binaire. |

Les plus gros contributeurs directs sont:

| Fichier | Taille brute | Usage runtime |
| --- | ---: | --- |
| `offline-data/full/room-import/watersheds.json` | 72.38 MB | Importe les bassins versants dans Room. |
| `modele_statistique/high_risk_overlay.onnx` | 45.24 MB | Overlay ML high-risk, charge si active. |
| `modele_statistique/model.onnx` | 31.59 MB | Modele principal prediction debit. |
| `modele_statistique/canyon_static_features.json` | 30.08 MB | Features statiques chargees en memoire. |
| `modele_statistique/runtime_feature_lookups.json` | 19.14 MB | Lookups runtime charges en memoire. |
| `offline-data/full/room-import/canyons.json` | 9.51 MB | Import catalogue core. |

Conclusion principale: l'optimisation significative ne viendra pas des ressources Android classiques, mais de la strategie data/ML/offline. Les quick wins existent, mais les gros gains demandent de modifier le mode de packaging et les formats de donnees.

## Priorites globales

| Priorite | Chantier | Impact attendu | Risque |
| --- | --- | --- | --- |
| P0 | Mesurer precisement AAB/APK et contenu package | Base de decision fiable | Faible |
| P0 | Exclure les assets ML non lus | Gain immediat, faible risque | Faible |
| P0 | Verifier splits ABI/langue et eviter APK universel | Gros gain sur APK distribue hors Play | Faible |
| P1 | Alléger ou differer `watersheds.json` | Gain taille majeur | Moyen |
| P1 | Remplacer import JSON par DB Room prepackagee | Gain startup/RAM majeur | Moyen |
| P1 | Transformer les JSON ML en format mobile compact | Gain taille/RAM/runtime | Moyen |
| P2 | Reduire/differer ONNX et overlay high-risk | Gain taille majeur | Eleve, depend metier ML |
| P2 | Optimiser carte, recherche, prediction et images | Gain fluidite | Moyen |
| P3 | Nettoyer features partielles et code mort | Gain maintenabilite | Moyen |

## Plan par expert metier

## Expert Build et Release Android

Mission: garantir que la taille mesuree correspond a ce que l'utilisateur telecharge, reduire le contenu package inutile et industrialiser les budgets de taille.

Constats:

- `app/build.gradle.kts:62-70` ajoute `src/main/assets`, `../offline-data/full/room-import` et `../modele_statistique` en entier.
- `release` active deja R8 et resource shrink via `app/build.gradle.kts:84-95`.
- `minifiedDebug` existe via `app/build.gradle.kts:101-114`, utile pour valider rapidement R8.
- `app/proguard-rules.pro:7-8` garde tout `io.ktor.**`, regle large susceptible de limiter R8.
- `app/build.gradle.kts:210` embarque `onnxruntime-android` et `app/build.gradle.kts:213` embarque MapLibre, deux dependances natives lourdes.
- Aucun bloc explicite `splits`, `bundle.language`, `androidResources.noCompress` ou dynamic feature n'a ete identifie.

Actions P0:

- Construire `:app:bundleRelease` et `:app:assembleRelease`, puis lister les 100 plus gros fichiers inclus.
- Comparer taille AAB, APK universel, APK split par ABI et taille installee via `bundletool`.
- Remplacer le `sourceSets` large par un dossier d'assets runtime explicite ou par des exclusions Gradle.
- Exclure au minimum `modele_statistique/runtime-lookups/**`, `high_risk_overlay_feature_spec.json`, `high_risk_overlay_metrics.json` et `offline-data/full/room-import/tracks.json` tant qu'il vaut `[]`.
- Ajouter une tache CI qui echoue si l'AAB, l'APK universel ou les assets depassent un budget defini.

Actions P1:

- Resserrer `app/proguard-rules.pro` pour Ktor et verifier qu'aucune serialization runtime ne casse.
- Mesurer l'impact reel de `material-icons-extended`; si R8 ne l'elimine pas suffisamment, remplacer par icones vectorielles locales.
- Verifier si `androidx.security.crypto` reste necessaire avec la strategie actuelle de stockage credentials.

Actions P2:

- Evaluer Play Asset Delivery ou dynamic features pour pack ML, pack watersheds et eventuellement pack carte.
- Definir une strategie de release distincte entre Play Store AAB et APK de test/distribution directe.

Validations:

```powershell
.\gradlew.bat --no-daemon :app:bundleRelease :app:assembleRelease :app:assembleMinifiedDebug
```

```powershell
bundletool build-apks --bundle=app\build\outputs\bundle\release\app-release.aab --output=C:\Users\antoi\AppData\Local\Temp\opencode\descente-canyon.apks --mode=default
bundletool get-size total --apks=C:\Users\antoi\AppData\Local\Temp\opencode\descente-canyon.apks --dimensions=ABI,LANGUAGE,SCREEN_DENSITY
```

```powershell
apkanalyzer apk summary app\build\outputs\apk\release\app-release.apk
apkanalyzer files list app\build\outputs\apk\release\app-release.apk
```

Livrables attendus:

- Rapport taille avant/apres avec top fichiers AAB/APK.
- Budget CI taille.
- Liste explicite des assets runtime packages.

## Expert Data Offline et Room

Mission: supprimer les couts de premier lancement et reduire le poids des donnees offline sans casser le mode catalogue local.

Constats:

- `EmbeddedAppDataImporter.kt:58-61` importe core puis watersheds.
- `EmbeddedAppDataImporter.kt:111-145` charge plusieurs JSON coeur en memoire, cree les entites et l'index de recherche, puis insere en Room.
- `EmbeddedAppDataImporter.kt:499-501` lit les JSON via `readText()` complet.
- `EmbeddedAppDataImporter.kt:333-359` streame deja `watersheds.json`, mais l'asset brut reste tres lourd.
- `offline-data/full/room-import/manifest.json:14-23` annonce 4127 canyons, 11037 geo points, 3694 watersheds et 0 tracks.
- `DatabaseModule.kt:349-366` cree une DB Room vide; aucun `createFromAsset` n'est utilise.
- `DescenteCanyonDatabase.kt:49` est en version 13 avec export schema actif.

Actions P0:

- Mesurer duree, RAM et taille DB finale sur clean install avec les JSON actuels.
- Ajouter des traces explicites par phase: lecture JSON, decode, mapping, insertion core, insertion watersheds, creation search index.
- Confirmer que `tracks.json` vide peut etre retire sans effet grace a `readOptionalJsonAsset("tracks.json")` dans `EmbeddedAppDataImporter.kt:327-330`.

Actions P1:

- Generer une DB Room prepackagee a partir de `offline-data/full/room-import`, puis utiliser `Room.databaseBuilder(...).createFromAsset(...)` ou equivalent.
- Conserver les champs utilisateur locaux (`isFavorite`, `isOffline`, debits pending, photos, meteo cache) dans des tables separees ou des migrations preservees.
- Scinder watersheds en deux tables ou deux artefacts: metadata legere (`canyonId`, `areaKm2`, `bbox`) et geometrie lourde chargee a la demande.
- Supprimer la geometrie de `watersheds.json` du base install si le produit accepte que le polygone soit telecharge ou active a la demande.

Actions P2:

- Remplacer les JSON import par un format binaire ou SQLite compressible par page.
- Etudier FTS5 Room pour la recherche au lieu de charger tout `search_index` en memoire.

Risques:

- Les migrations depuis anciennes versions peuvent casser si la DB prepackagee n'est pas compatible.
- La suppression ou le defer des geometries watershed peut degrader l'ecran carte bassin versant.
- Les flags locaux utilisateur ne doivent jamais etre ecrases pendant mise a jour dataset.

Validations:

- Clean install avec DB absente.
- Upgrade depuis DB v1, v8 et v13 si ces versions restent supportees.
- Comparaison des counts `manifest.json` avec les tables Room apres import/prepackage.
- Test offline complet apres premier lancement.

Livrables attendus:

- Prototype DB prepackagee.
- Tableau taille JSON compresse vs SQLite compresse vs DB installee.
- Decision produit sur geometrie watershed dans base install.

## Expert ML et Data Science

Mission: reduire l'empreinte modele et features sans degrader la securite des predictions de debit.

Constats:

- `EmbeddedDebitModelStore.kt:307-313` charge `feature_spec.json`, `thresholds.json`, `canyon_static_features.json`, `metrics.json`, `model.onnx`, `high_risk_overlay.json`.
- `EmbeddedDebitRuntimeLookupStore.kt:81-83` charge `runtime_feature_lookups.json`.
- `high_risk_overlay.json:3-5` active l'overlay et pointe vers `high_risk_overlay.onnx`.
- `model.onnx` pese 31.59 MB brut.
- `high_risk_overlay.onnx` pese 45.24 MB brut.
- `canyon_static_features.json` pese 30.08 MB brut.
- `runtime_feature_lookups.json` pese 19.14 MB brut.
- `EmbeddedDebitModelStore.kt:166-177` copie les ONNX depuis les assets vers `filesDir`, ce qui double le stockage apres usage.
- `PredictionWarmupCoordinator.kt:37-43` precharge le modele principal, mais pas l'overlay high-risk.

Actions P0:

- Confirmer les fichiers strictement requis au runtime mobile et exclure tous les artefacts d'entrainement/diagnostic.
- Produire un `metrics_mobile.json` minimal contenant seulement ce que `MetricsDto` consomme dans `EmbeddedDebitModelStore.kt:371-400`.
- Mesurer latence froide et chaude: copie asset, creation session ONNX, premiere inference, inference chaude, overlay.

Actions P1:

- Convertir `canyon_static_features.json` et `runtime_feature_lookups.json` en format columnar mobile.
- Eviter le chargement complet en memoire si seule une entree canyon est necessaire.
- Batcher les horizons de prediction pour eviter trois executions sequentielles.
- Precharger l'overlay si on le conserve, ou rendre son premier usage explicitement asynchrone.

Actions P2:

- Evaluer quantization ou simplification des modeles.
- Evaluer ONNX Runtime mobile/reduced ops ou un runtime plus specialise si les modeles sont tree-based.
- Comparer trois strategies produit: overlay embarque, overlay telecharge a la demande, overlay retire.

Risques:

- Retirer ou reduire `high_risk_overlay.onnx` peut reduire le rappel des cas HIGH.
- Un format feature compact doit garantir l'ordre exact des features et les valeurs par defaut.
- Toute optimisation ML doit etre validee sur les jeux strict holdout et validation recente.

Validations:

- Tests contrat ONNX existants sur JVM et Android.
- Diff prediction avant/apres sur un echantillon de canyons et dates.
- Comparaison metriques HIGH precision/recall/F1 avec et sans overlay.
- Benchmarks latence P50/P95 sur appareil modeste.

Livrables attendus:

- Matrice taille/latence/qualite par strategie ML.
- Nouveau format mobile de features et lookups.
- Decision go/no-go sur overlay high-risk embarque.

## Expert Performance Android

Mission: reduire jank, temps de startup, cout CPU/RAM et latence des ecrans principaux.

Constats:

- `MainActivity.kt:102-109` affiche un etat bloquant si l'import est requis.
- `AppStartupCoordinator.kt:64-81` lance import core puis watersheds sur IO pendant initialization.
- `AppStartupCoordinator.kt:83-95` restaure l'auth pendant startup.
- `AppStartupCoordinator.kt:104-116` lance warmup recherche puis prediction peu apres le lancement.
- `SearchCatalogWarmupCoordinator.kt:33-41` charge le catalogue complet.
- `PredictionWarmupCoordinator.kt:37-43` charge lookups, spec, thresholds, static features et session ONNX.
- `EmbeddedDebitRuntimeLookupStore.kt:35-43` lit et parse `runtime_feature_lookups.json` entier.
- `EmbeddedDebitModelStore.kt:87-94` lit et parse `canyon_static_features.json` entier.
- `DebitPredictionRepositoryImpl.kt` effectue les horizons de prediction sequentiellement.

Actions P0:

- Ajouter instrumentation startup/import/search/prediction avec budgets.
- Deplacer la synchro des debits pending apres initialization complete.
- Eviter tout appel reseau si `ConnectivityObserver` indique offline.
- Activer StrictMode en debug/minifiedDebug pour detecter disk/network sur main thread.

Actions P1:

- Remplacer import JSON par DB prepackagee avec l'expert Data.
- Rendre les warmups adaptatifs: ne pas les lancer pendant interaction utilisateur ou battery saver.
- Batcher prediction 3 horizons ou reutiliser les objets intermediaires.
- Precompiler les Regex dans `SearchTextNormalizer.kt`.

Actions P2:

- Ajouter Baseline Profile et Macrobenchmark.
- Ajouter JankStats sur Home, Search, Map, Detail et Gallery.
- Fractionner les gros `UiState` detail canyon pour reduire recompositions inutiles.

Validations:

- Cold startup clean install sans DB.
- Cold startup avec DB existante.
- Warm/hot startup.
- Search typing P95.
- Detail canyon load P95.
- Prediction froide et chaude.
- Taux frames janky sur carte et recherche.

Budgets cibles initiaux:

| Metrique | Budget cible |
| --- | ---: |
| Hot startup | < 1 s |
| Cold startup avec DB existante | < 2 s hors reseau |
| Search compute P95 | < 50 ms hors main |
| Map camera idle update P95 | < 32 ms |
| Prediction chaude 3 horizons | < 100 ms hors reseau |
| Frames janky ecrans clefs | < 5 % |

## Expert Cartographie et MapLibre

Mission: conserver une carte utile tout en reduisant cout CPU, RAM et poids des geometries.

Constats:

- MapLibre est une dependance native lourde via `app/build.gradle.kts:213`.
- `MapLibreView.kt` est volumineux et gere interop/lifecycle/rendering dans un seul fichier.
- La carte reconstruit des `FeatureCollection` et signatures de marqueurs sur updates Compose/camera.
- Les geometries watershed peuvent etre volumineuses et sont stockees en GeoJSON brut dans Room.
- `CanyonLocalStore.kt` charge apparemment le watershed complet avec geometrie au lieu d'une projection legere.

Actions P0:

- Mesurer le cout de rendu carte globale avec environ 4000 canyons.
- Identifier les chemins ou la geometrie watershed est chargee alors que seule `areaKm2` ou `bbox` est necessaire.

Actions P1:

- Creer des projections DAO distinctes: metadata watershed sans geometrie, detail watershed avec geometrie.
- Cacher les `FeatureCollection` par signature stable.
- Deplacer creation markers, tri, hash et clustering hors main thread.
- Throttler les updates de bounds/camera idle.

Actions P2:

- Simplifier ou vectoriser les geometries watershed.
- Charger les geometries de bassins a la demande par zone ou par canyon.
- Evaluer si certains styles/tiles/glyphs reseau peuvent etre caches ou preconfigures offline.

Risques:

- Trop simplifier les bassins peut reduire la valeur metier de l'ecran bassin versant.
- Le defer de geometrie doit rester clair dans l'UX offline.

Validations:

- Pan/zoom carte sur appareil modeste.
- Ouverture carte detail canyon avec et sans bassin.
- Tests offline avec tuiles et sans reseau.
- Comparaison visuelle des geometries simplifiees.

## Expert Architecture Android et Maintenabilite

Mission: supprimer l'ambiguite fonctionnelle, reduire les couches pass-through et rendre les gros ecrans maintenables.

Constats:

- Plusieurs use cases sont de simples pass-through: `SyncPendingDebitsUseCase`, `SubmitDebitUseCase`, `GetCanyonDetailUseCase`, `GetCanyonPreviewUseCase`, `GetCanyonWeatherUseCase`, `GetCanyonDebitPredictionsUseCase`, `DownloadPhotoForOfflineUseCase`.
- Les ViewModels melangent parfois use cases et repositories directs.
- Fonction offline canyon complete partielle dans `CanyonRepositoryImpl.kt:129-156`, avec TODO suppression tuiles/photos.
- `MapConfig.MAP_OFFLINE_RADIUS_KM` existe mais le rayon `3.0` est code en dur ailleurs.
- Ancienne voie remote search/nearby probable: `SearchParser`, `NearbyParser`, `MapIndexParser`, `MapIndexRemoteSource*`, `NearbyCanyonRemoteSource*`.
- Deux systemes de theme coexistent: `ui.theme` et `ui.design`.
- Fichiers volumineux: `CanyonDetailScreen.kt`, `HomeScreen.kt`, `MapLibreView.kt`, `SearchScreen.kt`, `SearchFiltersSheet.kt`.
- Schémas `AppDatabase` anciens existent alors que la DB active est `DescenteCanyonDatabase`.

Actions P0:

- Faire une decision produit explicite pour chaque fonctionnalite partielle: offline canyon complet, tracks, remote search/nearby, deep links.
- Supprimer les assets/drawables/tests clairement morts apres validation.
- Corriger ou documenter les deep links: le manifest declare `descente-canyon.com`, l'app doit router `Intent.data` vers detail canyon.

Actions P1:

- Fusionner les use cases pass-through ou les transformer en vrais interactors metier.
- Standardiser l'injection ViewModel: soit use cases, soit repositories, mais pas melange arbitraire.
- Unifier `ui.theme` dans `ui.design` et supprimer les tokens divergents.
- Scinder `MapLibreView.kt` en lifecycle adapter, style manager, marker renderer, watershed renderer.
- Scinder `CanyonDetailScreen.kt` et `HomeScreen.kt` par sections testables.

Actions P2:

- Definir une politique de support migration Room: versions minimales supportees, schemas conserves, tests migration.
- Sortir les donnees generees massives du repo si elles ne sont pas des sources de verite.

Suppressions probables a valider:

| Element | Confiance | Validation requise |
| --- | --- | --- |
| `modele_statistique/runtime-lookups/**` dans assets Android | Haute | AAB content + prediction OK. |
| `high_risk_overlay_feature_spec.json` package | Haute | Aucune reference runtime. |
| `high_risk_overlay_metrics.json` package | Haute | Aucune reference runtime. |
| `tracks.json` vide package | Haute | Import optionnel OK. |
| `map_marker_user.xml`, `map_marker_parking.xml`, `map_marker_cluster.xml` | Haute | Build + rendu carte. |
| Dependances test `turbine`, `espresso-core`, `uiautomator` | Haute a moyenne | Aucun import + test suite OK. |
| Ancienne remote search/nearby | Moyenne | Confirmer aucune UX dependante. |
| Offline canyon complet | Moyenne | Decision produit. |
| `LegacyPhotoStorageMigrator` | Basse | Fenetre de migration suffisante. |

Validations:

- Build release minifie.
- Tests unitaires et androidTest.
- Tests navigation deep links cold start et `onNewIntent`.
- Captures visuelles Home/Search/Map/Detail apres refactor design.

## Expert Reseau, Sync et Offline

Mission: eviter les appels reseau inutiles, clarifier le mode offline et reduire consommation batterie/reseau.

Constats:

- `MainActivity.kt:68-72` collecte la connectivite et lance `syncPendingDebitsIfOnline` tot dans le cycle.
- `AppStartupCoordinator.kt:83-95` restaure l'auth pendant startup.
- Les refresh detail canyon peuvent lancer photos, debits, preview, full detail, EDF, meteo et prediction proches dans le temps.
- `CanyonRepositoryImpl.kt:129-151` telecharge offline de facon applicative, pas via WorkManager avec progress/annulation.
- `CanyonRepositoryImpl.kt:154-157` note que la suppression tuiles/photos reste TODO.
- `NotificationSyncScheduler.kt` planifie une synchro periodique toutes les 30 min connectee.

Actions P0:

- Gater sync pending apres initialization complete.
- Court-circuiter les refresh reseau quand l'app est offline.
- Ajouter des logs/metrics de nombre d'appels reseau au detail canyon.

Actions P1:

- Ajouter TTL cache meteo/EDF/prediction support.
- Dedupliquer les chargements detail simultanes par canyon.
- Transformer le telechargement offline canyon en WorkManager avec progress, contraintes, annulation et cleanup.

Actions P2:

- Separer retry notification debits/forums pour eviter qu'une source bloque l'autre.
- Ajouter backoff explicite et contraintes batterie si necessaire.

Validations:

- Scenario offline reel: detail canyon affiche cache sans timeout long.
- Scenario timeout reseau: contenu local visible rapidement.
- Tests WorkManager avec contraintes reseau et retry.
- Verification cleanup offline: tuiles, photos, flags DB.

## Expert Produit et UX

Mission: arbitrer les compromis taille vs valeur utilisateur et rendre les chargements differes comprehensibles.

Questions a trancher:

- Le catalogue complet doit-il etre disponible immediatement apres installation?
- Les polygones de bassins versants doivent-ils etre disponibles offline par defaut?
- La prediction debit doit-elle fonctionner sans telechargement additionnel?
- L'overlay high-risk est-il une exigence securite bloquante dans le base install?
- Le mode offline canyon complet est-il une fonctionnalite produit a terminer ou a retirer?
- Les tracks GPX sont-elles prevues a court terme alors que `tracks` vaut 0?
- Les deep links externes `descente-canyon.com` sont-ils prioritaires?

Decisions recommandees:

- Garder en base install: catalogue core, recherche, fiches principales, favoris, saisie debit, navigation de base.
- Differer ou telecharger a la demande: geometries watershed detaillees, packs ML avancés, cartes offline, photos.
- Terminer ou supprimer le mode offline canyon complet; l'etat partiel augmente la dette et l'incomprehension UX.
- Si la prediction debit est une promesse centrale, afficher clairement l'etat de disponibilite du modele si delivery differé.

Validations UX:

- Test premier lancement sans reseau.
- Test lancement avec modele ou watersheds non encore telecharges.
- Test carte sans geometrie detaillee.
- Test suppression donnees offline.

Livrables attendus:

- Matrice fonctionnalite/base install/on-demand/retire.
- Copy UX pour chargement modele, donnees bassin et offline.
- Definition de priorite pour deep links, tracks et offline canyon.

## Expert QA, CI et Observabilite

Mission: rendre les optimisations mesurables et proteger contre les regressions.

Actions P0:

- Ajouter verification CI de taille AAB/APK et contenu assets.
- Ajouter tests smoke minifiedDebug pour prediction ONNX, import/prepackage DB, navigation principale.
- Ajouter un rapport automatique des 50 plus gros fichiers package.

Actions P1:

- Ajouter module Macrobenchmark ou configuration benchmark dediee.
- Ajouter tests migration Room pour versions supportees.
- Ajouter tests offline: pas de reseau, cache disponible, timeouts simules.
- Ajouter tests deep link `https://www.descente-canyon.com/canyoning/canyon/<id>/...`.

Actions P2:

- Ajouter JankStats et traces Perfetto ciblees.
- Ajouter alerte si startup, search ou prediction depasse budgets.

Commandes de reference:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

```powershell
.\gradlew.bat --no-daemon :app:assembleMinifiedDebug
```

```powershell
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

Livrables attendus:

- Tableau de bord taille et performance.
- Seuils CI documentes.
- Tests de non-regression migration/offline/deep links.

## Roadmap proposee

## Phase 0: Mesure et garde-fous

Duree cible: 1 a 2 jours.

Actions:

- Generer AAB/APK release et top fichiers packages.
- Mesurer taille par ABI/langue via `bundletool`.
- Ajouter scripts ou taches CI de budget taille.
- Mesurer startup clean install, startup DB existante, prediction froide/chaude, recherche et carte.

Critere de sortie:

- Les chiffres de reference sont partages et reproductibles.
- Chaque optimisation future peut etre comparee objectivement.

## Phase 1: Quick wins taille faible risque

Duree cible: 1 a 3 jours.

Actions:

- Filtrer les assets ML non lus.
- Retirer `tracks.json` vide du package si compatible.
- Resserrer les keep rules R8 les plus larges apres validation.
- Retirer dependances et ressources mortes confirmees.

Critere de sortie:

- AAB/APK plus petits sans changement fonctionnel visible.
- Tests prediction, import et navigation OK.

## Phase 2: Donnees offline et startup

Duree cible: 1 a 2 semaines.

Actions:

- Prototype DB Room prepackagee.
- Scinder watersheds metadata/geometrie.
- Remplacer import JSON bloquant par DB prepackagee ou import incremental non bloquant.
- Adapter migrations et preservation des donnees utilisateur.

Critere de sortie:

- Premier lancement significativement plus rapide.
- RAM et CPU import reduits.
- Taille assets offline reduite ou mieux justifiee.

## Phase 3: ML mobile compact

Duree cible: 1 a 3 semaines selon strategie ML.

Actions:

- Generer formats compacts pour static features et runtime lookups.
- Evaluer reduction/delivery de `model.onnx` et `high_risk_overlay.onnx`.
- Batcher prediction horizons.
- Valider qualite prediction avant/apres.

Critere de sortie:

- Taille ML reduite avec metriques metier acceptees.
- Latence prediction froide et chaude mesuree.

## Phase 4: Fluidite UI et carte

Duree cible: 1 a 2 semaines.

Actions:

- Optimiser MapLibre markers, FeatureCollections et loading watershed.
- Optimiser recherche et normalisation.
- Configurer Coil pour tailles d'images et caches explicites.
- Ajouter Baseline Profile et benchmarks.

Critere de sortie:

- Jank reduit sur carte, recherche et detail.
- Budgets performance respectes sur appareil cible.

## Phase 5: Dette et simplification produit

Duree cible: continu, 1 sprint dedie recommande.

Actions:

- Decider et supprimer/cabler offline canyon complet, remote search/nearby, tracks, deep links.
- Fusionner use cases pass-through.
- Unifier design system.
- Scinder les gros fichiers UI.
- Nettoyer migrations/schemas selon politique de support.

Critere de sortie:

- Moins de chemins morts.
- Architecture plus coherente.
- Fonctionnalites partielles soit terminees, soit retirees.

## Annexes techniques

Fichiers et lignes clefs:

| Sujet | Reference |
| --- | --- |
| Assets embarques | `app/build.gradle.kts:62-70` |
| Release shrink | `app/build.gradle.kts:84-95` |
| Minified debug | `app/build.gradle.kts:101-114` |
| Dependances ONNX/MapLibre | `app/build.gradle.kts:210-214` |
| R8 Ktor large | `app/proguard-rules.pro:7-8` |
| Import startup | `AppStartupCoordinator.kt:64-81` |
| UI bloquante import | `MainActivity.kt:102-109` |
| Lecture JSON complete | `EmbeddedAppDataImporter.kt:499-501` |
| Import watersheds streaming | `EmbeddedAppDataImporter.kt:333-359` |
| DB Room version | `DescenteCanyonDatabase.kt:49` |
| Creation Room vide | `DatabaseModule.kt:349-366` |
| Assets ML lus | `EmbeddedDebitModelStore.kt:307-313` |
| Lookups ML lus | `EmbeddedDebitRuntimeLookupStore.kt:81-83` |
| Copie ONNX vers filesDir | `EmbeddedDebitModelStore.kt:166-177` |
| Warmup prediction | `PredictionWarmupCoordinator.kt:37-43` |
| Offline canyon partiel | `CanyonRepositoryImpl.kt:129-156` |
| Manifest data counts | `offline-data/full/room-import/manifest.json:14-23` |
| Deep link manifest | `app/src/main/AndroidManifest.xml:40-49` |

Commandes utiles:

```powershell
.\gradlew.bat --no-daemon :app:dependencies --configuration releaseRuntimeClasspath
```

```powershell
.\gradlew.bat --no-daemon :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency com.microsoft.onnxruntime
.\gradlew.bat --no-daemon :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency org.maplibre.gl
.\gradlew.bat --no-daemon :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency material-icons-extended
.\gradlew.bat --no-daemon :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency io.ktor
```

```powershell
python -c "import zipfile; p='app/build/outputs/bundle/release/app-release.aab'; z=zipfile.ZipFile(p); rows=sorted(((i.file_size,i.compress_size,i.filename) for i in z.infolist()), reverse=True)[:80]; [print(f'{a}\t{b}\t{n}') for a,b,n in rows]"
```

```powershell
python -c "import gzip; from pathlib import Path; dirs=['app/src/main/assets','offline-data/full/room-import','modele_statistique']; [print(d, sum(p.stat().st_size for p in Path(d).rglob('*') if p.is_file()), sum(len(gzip.compress(p.read_bytes(),9)) for p in Path(d).rglob('*') if p.is_file())) for d in dirs]"
```

Definition de done globale:

- Taille AAB/APK mesuree et budgetee en CI.
- Assets packages limites aux fichiers runtime strictement necessaires.
- Startup sans import JSON lourd sur chemin normal.
- Prediction debit mesuree et qualite validee apres optimisation ML.
- Carte et recherche respectent les budgets jank/latence.
- Fonctionnalites partielles officiellement terminees ou retirees.
