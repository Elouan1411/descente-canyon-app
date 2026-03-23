package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.DebitEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.PhotoEntity
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import java.time.LocalDate

// --- Entity -> Domain ---

fun CanyonEntity.toDomain(): Canyon = Canyon(
    id = id,
    nom = nom,
    nomComplet = nomComplet,
    pays = pays,
    region = region,
    departement = departement,
    commune = commune,
    massif = massif,
    cotation = cotation,
    altitudeDepart = altitudeDepart,
    denivele = denivele,
    longueur = longueur,
    cascadeMax = cascadeMax,
    cordeMin = cordeMin,
    tempsApproche = tempsApproche,
    tempsDescente = tempsDescente,
    tempsRetour = tempsRetour,
    navette = navette,
    interet = interet,
    nbVotes = nbVotes,
    url = url,
    isOffline = isOffline,
    lastUpdated = lastUpdated,
)

fun CanyonEntity.toSummary(): CanyonSummary = CanyonSummary(
    id = id,
    nom = nom,
    pays = pays,
    departement = departement,
    cotation = cotation,
    interet = interet,
    url = url,
    isOffline = isOffline,
)

fun CanyonEntity.toDetail(
    geoPoints: List<GeoPointEntity>,
    photos: List<PhotoEntity>,
    debits: List<DebitEntity>,
): CanyonDetail = CanyonDetail(
    canyon = toDomain(),
    accesAval = accesAval,
    accesAmont = accesAmont,
    approche = approche,
    descente = descente,
    retour = retour,
    engagement = engagement,
    periode = periode,
    geoPoints = geoPoints.map { it.toDomain() },
    photos = photos.map { it.toDomain() },
    debits = debits.map { it.toDomain() },
)

fun GeoPointEntity.toDomain(): GeoPoint = GeoPoint(
    id = id,
    canyonId = canyonId,
    type = try { GeoPointType.valueOf(type) } catch (_: Exception) { GeoPointType.UNKNOWN },
    latitude = latitude,
    longitude = longitude,
    label = label,
)

fun DebitEntity.toDomain(): Debit = Debit(
    id = id,
    canyonId = canyonId,
    date = DateParser.parseToLocalDate(date) ?: LocalDate.of(1970, 1, 1),
    niveau = try { NiveauDebit.valueOf(niveau) } catch (_: Exception) { NiveauDebit.INCONNU },
    auteur = auteur,
    commentaire = commentaire,
)

fun PhotoEntity.toDomain(): CanyonPhoto = CanyonPhoto(
    id = id,
    canyonId = canyonId,
    url = url,
    thumbnailUrl = thumbnailUrl,
    auteur = auteur,
    description = description,
    localPath = localPath,
)

// --- Scraped DTO -> Entity ---

fun ScrapedCanyonDetail.toEntity(): CanyonEntity = CanyonEntity(
    id = id,
    nom = nom,
    nomComplet = nomComplet,
    pays = pays,
    region = region,
    departement = departement,
    commune = commune,
    massif = massif,
    cotation = cotation,
    altitudeDepart = altitudeDepart,
    denivele = denivele,
    longueur = longueur,
    cascadeMax = cascadeMax,
    cordeMin = cordeMin,
    tempsApproche = tempsApproche,
    tempsDescente = tempsDescente,
    tempsRetour = tempsRetour,
    navette = navette,
    interet = interet,
    nbVotes = nbVotes,
    url = url,
    accesAval = accesAval,
    accesAmont = accesAmont,
    approche = approche,
    descente = descente,
    retour = retour,
    engagement = engagement,
    periode = periode,
)

fun ScrapedGeoPoint.toEntity(canyonId: Int): GeoPointEntity = GeoPointEntity(
    canyonId = canyonId,
    type = type,
    latitude = latitude,
    longitude = longitude,
    label = label,
)

fun ScrapedDebit.toEntity(): DebitEntity = DebitEntity(
    canyonId = canyonId,
    date = DateParser.parseToIsoString(date) ?: date,
    niveau = niveauRaw,
    auteur = auteur,
    commentaire = commentaire,
)

fun ScrapedPhoto.toEntity(): PhotoEntity = PhotoEntity(
    canyonId = canyonId,
    url = url,
    thumbnailUrl = thumbnailUrl,
    auteur = auteur,
    description = description,
)

fun ScrapedCanyonSummary.toEntity(): CanyonEntity = CanyonEntity(
    id = id,
    nom = nom,
    nomComplet = nom,
    pays = pays,
    departement = departement,
    commune = "",
    cotation = cotation,
    url = url,
)
