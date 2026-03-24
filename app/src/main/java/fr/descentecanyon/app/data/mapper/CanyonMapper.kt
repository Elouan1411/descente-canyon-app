package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.DebitEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.PhotoEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.domain.model.RegulationAttachment
import fr.descentecanyon.app.domain.model.ResourceType
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun String.normalizeCountryName(): String {
    if (length !in 2..3) return this

    val code = uppercase()
    val specialCases = mapOf(
        "RE" to "Reunion",
        "MQ" to "Martinique",
        "GP" to "Guadeloupe",
        "GF" to "Guyane",
        "NC" to "Nouvelle-Caledonie",
        "PF" to "Polynesie francaise",
        "YT" to "Mayotte",
    )
    specialCases[code]?.let { return it }

    return Locale("", code).getDisplayCountry(Locale.FRENCH)
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.FRENCH) else char.toString() }
        ?: this
}

private val mapperJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class RegulationAttachmentPayload(val label: String, val url: String)

private fun List<String>.toJsonString(): String? {
    if (isEmpty()) return null
    return mapperJson.encodeToString(this)
}

private fun String?.fromJsonStringList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching { mapperJson.decodeFromString<List<String>>(this) }.getOrDefault(emptyList())
}

private fun List<RegulationAttachment>.toJsonAttachments(): String? {
    if (isEmpty()) return null
    val payload = map { RegulationAttachmentPayload(label = it.label, url = it.url) }
    return mapperJson.encodeToString(payload)
}

private fun String?.fromJsonAttachments(): List<RegulationAttachment> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching {
        mapperJson.decodeFromString<List<RegulationAttachmentPayload>>(this).map {
            RegulationAttachment(label = it.label, url = it.url)
        }
    }.getOrDefault(emptyList())
}

// --- Entity -> Domain ---

fun CanyonEntity.toDomain(): Canyon = Canyon(
    id = id,
    nom = nom,
    nomComplet = nomComplet,
    pays = pays,
    region = region,
    departement = departement,
    commune = commune,
    communes = communesJson.fromJsonStringList(),
    massif = massif,
    bassin = bassin,
    coursEau = coursEau,
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
    hasSpecificRegulation = hasSpecificRegulation,
    isOffline = isOffline,
    lastUpdated = lastUpdated,
)

fun CanyonEntity.toSummary(): CanyonSummary = CanyonSummary(
    id = id,
    nom = nom,
    pays = pays.normalizeCountryName(),
    departement = departement,
    cotation = cotation,
    interet = interet,
    url = url,
    isOffline = isOffline,
)

fun CanyonEntity.toDetail(
    geoPoints: List<GeoPointEntity>,
    bibliography: List<BibliographyEntryEntity>,
    regulations: List<RegulationTextEntity>,
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
    geologie = geologie,
    historique = historique,
    remarques = remarques,
    geoPoints = geoPoints.map { it.toDomain() },
    bibliography = bibliography.map { it.toDomain() },
    regulations = regulations.map { it.toDomain() },
    photos = photos.map { it.toDomain() },
    debits = debits.map { it.toDomain() },
)

fun BibliographyEntryEntity.toDomain(): BibliographyEntry = BibliographyEntry(
    id = id,
    kind = runCatching { BibliographyKind.valueOf(kind) }.getOrDefault(BibliographyKind.RESOURCE),
    resourceType = resourceType?.let { runCatching { ResourceType.valueOf(it) }.getOrNull() },
    title = title,
    authors = authorsJson.fromJsonStringList(),
    publicationYear = publicationYear,
    reference = reference,
    editor = editor,
    status = status,
    scale = scale,
    detailUrl = detailUrl,
    url = url,
)

fun RegulationTextEntity.toDomain(): Regulation = Regulation(
    id = id,
    status = status,
    action = action,
    title = title,
    summary = summary,
    remark = remark,
    details = details,
    effectiveDate = effectiveDate,
    textUrl = textUrl,
    attachments = attachmentsJson.fromJsonAttachments(),
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
    canyonNom = null,
    date = DateParser.parseToLocalDate(date) ?: LocalDate.of(1970, 1, 1),
    niveau = try { NiveauDebit.valueOf(niveau) } catch (_: Exception) { NiveauDebit.INCONNU },
    auteur = auteur,
    isDescended = isDescended,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    commentaire = commentaire,
)

fun ScrapedDebit.toLatestDomain(): Debit = Debit(
    id = buildLatestDebitStableId(),
    canyonId = canyonId,
    canyonNom = canyonNom.ifBlank { null },
    date = DateParser.parseToLocalDate(date) ?: LocalDate.of(LocalDate.now().year, 1, 1),
    niveau = try { NiveauDebit.valueOf(niveauRaw) } catch (_: Exception) { NiveauDebit.INCONNU },
    auteur = auteur,
    isDescended = isDescended,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    commentaire = commentaire,
)

private fun ScrapedDebit.buildLatestDebitStableId(): Long {
    return listOf(canyonId.toString(), canyonNom, date, niveauRaw, auteur.orEmpty(), commentaire.orEmpty())
        .joinToString("|")
        .hashCode()
        .toLong()
        .let { if (it == 0L) 1L else kotlin.math.abs(it) }
}

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
    isDescended = isDescended,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
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
