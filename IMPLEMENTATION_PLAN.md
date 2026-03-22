# Plan d'implementation - Descente-Canyon App

## Vue d'ensemble

MVP Android (scraper direct) pour consulter les fiches canyons de descente-canyon.com,
avec carte MapLibre integree, mode offline, et signalement de debits.

## Blocs d'implementation

### Bloc 1 - Scraper HTML (fondation)
- Parser les fiches canyon (resume + description/topo)
- Parser les debits (par canyon + derniers debits globaux)
- Parser la carte/geopoints (extraction des coordonnees GPS)
- Parser les photos
- Parser les resultats de recherche
- Rate limiting (Semaphore) + withContext(Dispatchers.IO)
- Tests unitaires avec HTML sauvegarde en resources (~15-20 tests)

### Bloc 2 - Authentification / Session
- Login POST /login avec cookie de session
- Stockage credentials dans EncryptedSharedPreferences
- AuthManager avec Flow<AuthState>
- Tests unitaires (~5-8 tests)

### Bloc 3 - Couche Data (corrections + completion)
- Fix B-4/B-5 : preserver isFavorite/isOffline lors des REPLACE
- Fix B-1 : corriger Flow.collect bloquant dans searchByName
- Fix B-2 : debits offline avec .first()
- Fix B-6 : try-catch sur LocalDate.parse()
- Fix AN-5 : fallbackToDestructiveMigration()
- DispatcherModule pour injection des dispatchers
- Tests unitaires + integration Room (~15-20 tests)

### Bloc 4 - ViewModels + Ecrans fonctionnels
- HomeViewModel + HomeScreen (derniers debits)
- SearchViewModel + SearchScreen (recherche avec debounce)
- CanyonDetailViewModel + CanyonDetailScreen (fiche complete avec tabs)
- FavoritesViewModel + FavoritesScreen
- AuthViewModel + LoginScreen
- DebitFormViewModel + DebitFormScreen
- Tests unitaires des ViewModels avec Turbine (~15-20 tests)

### Bloc 5 - Integration MapLibre
- MapLibreView composable wrapper
- Tuiles OpenTopoMap
- Marqueurs par type (parking, entree, sortie)
- Position utilisateur + permission runtime
- Navigation externe vers Google Maps/Waze
- Tests unitaires du ViewModel (~5 tests)

### Bloc 6 - Mode offline + signalement de debit
- Telechargement fiche + geopoints + tuiles carte
- Detection connectivite + basculement auto
- Formulaire de signalement de debit (POST)
- File d'attente offline pour debits
- Tests (~10 tests)

## Ordre d'execution

```
Bloc 1 -> Bloc 2 -> Bloc 3 -> Bloc 4 -+-> Bloc 6
                                Bloc 5 -+
```

## Estimation totale : 17-24 jours, ~65-83 tests unitaires
