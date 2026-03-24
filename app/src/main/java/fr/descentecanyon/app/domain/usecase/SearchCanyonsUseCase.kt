package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchResultSet
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.matches
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SearchCanyonsUseCase @Inject constructor(
    private val canyonRepository: CanyonRepository,
) {
    fun observeCatalog() = canyonRepository.observeSearchCatalog()

    operator fun invoke(
        catalog: List<CanyonSearchItem>,
        criteria: SearchCriteria,
    ): SearchResultSet {
        val normalizedQuery = criteria.query.normalizeForSearch()
        val baseMatches = catalog.asSequence()
            .filter { matchesBaseFilters(it, criteria, normalizedQuery) }
            .toList()

        val availableCountries = baseMatches.asSequence()
            .flatMap { it.countryTokens.asSequence() }
            .distinct()
            .sorted()

        val countryMatches = baseMatches.asSequence()
            .filter { matchesCountry(it, criteria.selectedCountry) }
            .toList()

        val availableDepartments = countryMatches.asSequence()
            .flatMap { it.departmentTokens.asSequence() }
            .distinct()
            .sorted()

        val results = countryMatches.asSequence()
            .filter { matchesDepartment(it, criteria.selectedDepartment) }
            .toList()

        if (criteria.shouldDeferBroadResults()) {
            return SearchResultSet(
                results = emptyList(),
                availableCountries = availableCountries.toList(),
                availableDepartments = availableDepartments.toList(),
                totalResultsCount = results.size,
                isResultListDeferred = true,
            )
        }

        return SearchResultSet(
            results = sortItems(results, criteria),
            availableCountries = availableCountries.toList(),
            availableDepartments = availableDepartments.toList(),
            totalResultsCount = results.size,
        )
    }

    private fun matchesBaseFilters(
        item: CanyonSearchItem,
        criteria: SearchCriteria,
        normalizedQuery: String,
    ): Boolean {
        return matchesQuery(item, normalizedQuery) &&
            (!criteria.favoritesOnly || item.isFavorite) &&
            item.cotationRating.matches(criteria) &&
            (criteria.interestMin == null || (item.interet ?: Float.NEGATIVE_INFINITY) >= criteria.interestMin) &&
            (!criteria.regulationOnly || item.hasSpecificRegulation) &&
            (!criteria.shuttleOnly || item.hasNavette) &&
            criteria.altitudeRange.matches(item.altitudeDepart) &&
            criteria.elevationRange.matches(item.denivele) &&
            criteria.lengthRange.matches(item.longueur) &&
            criteria.maxWaterfallRange.matches(item.cascadeMax) &&
            criteria.ropeRange.matches(item.cordeMin)
    }

    private fun matchesCountry(item: CanyonSearchItem, country: String?): Boolean {
        return country == null || item.countryTokens.any { it.equals(country, ignoreCase = true) }
    }

    private fun matchesDepartment(item: CanyonSearchItem, department: String?): Boolean {
        return department == null || item.departmentTokens.any { it.equals(department, ignoreCase = true) }
    }

    private fun sortItems(
        items: List<CanyonSearchItem>,
        criteria: SearchCriteria,
    ): List<CanyonSearchItem> {
        val comparator = when (criteria.sortField) {
            SearchSortField.RELEVANCE -> compareBy<CanyonSearchItem> {
                relevanceScore(it, criteria.query)
            }
                .thenBy { it.interet ?: Float.NEGATIVE_INFINITY }
                .thenBy { it.nbVotes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.INTEREST -> compareNullable<CanyonSearchItem, Float>(selector = { item -> item.interet })
                .thenBy { it.nbVotes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.POPULARITY -> compareBy<CanyonSearchItem> { it.nbVotes }
                .then(compareNullable<CanyonSearchItem, Float>(selector = { item -> item.interet }))
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.DIFFICULTY -> Comparator { left, right ->
                compareDifficultyAscending(left.cotationRating, right.cotationRating)
                    .takeIf { it != 0 }
                    ?: compareCotationFallback(left.cotationRating, right.cotationRating)
                        .takeIf { it != 0 }
                    ?: left.nom.compareTo(right.nom, ignoreCase = true)
            }

            SearchSortField.ELEVATION -> compareNullable<CanyonSearchItem, Int>(selector = { item -> item.denivele })
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.LENGTH -> compareNullable<CanyonSearchItem, Int>(selector = { item -> item.longueur })
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.MAX_WATERFALL -> compareNullable<CanyonSearchItem, Int>(selector = { item -> item.cascadeMax })
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.DISTANCE -> compareByDistance(criteria)
        }

        val sorted = items.sortedWith(comparator)
        return when (criteria.sortDirection) {
            SortDirection.ASC -> sorted
            SortDirection.DESC -> reverseKeepingUnknownsLast(sorted, criteria.sortField)
        }
    }

    private fun matchesQuery(item: CanyonSearchItem, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return true
        return item.searchableText.contains(normalizedQuery)
    }

    private fun relevanceScore(item: CanyonSearchItem, query: String): Int {
        val normalizedQuery = query.normalizeForSearch()
        if (normalizedQuery.isBlank()) return 0

        val normalizedName = item.nom.normalizeForSearch()
        val normalizedFullName = item.nomComplet.normalizeForSearch()
        return when {
            normalizedName == normalizedQuery -> 500
            normalizedName.startsWith(normalizedQuery) -> 400
            normalizedName.contains(normalizedQuery) -> 300
            normalizedFullName.startsWith(normalizedQuery) -> 250
            normalizedFullName.contains(normalizedQuery) -> 200
            else -> 100
        }
    }

    private fun compareCotationFallback(left: CotationRating, right: CotationRating): Int {
        return compareValuesBy(
            left,
            right,
            { it.vertical ?: Int.MAX_VALUE },
            { it.aquatic ?: Int.MAX_VALUE },
            { it.engagement ?: Int.MAX_VALUE },
        )
    }

    private fun compareDifficultyAscending(left: CotationRating, right: CotationRating): Int {
        val leftProfile = left.difficultyProfileDescending()
        val rightProfile = right.difficultyProfileDescending()
        return when {
            leftProfile == null && rightProfile == null -> 0
            leftProfile == null -> 1
            rightProfile == null -> -1
            else -> {
                leftProfile.zip(rightProfile).firstOrNull { it.first != it.second }
                    ?.let { (leftValue, rightValue) -> leftValue.compareTo(rightValue) }
                    ?: 0
            }
        }
    }

    private fun compareByDistance(criteria: SearchCriteria): Comparator<CanyonSearchItem> {
        val latitude = criteria.userLatitude
        val longitude = criteria.userLongitude
        return compareNullable<CanyonSearchItem, Double>(selector = { item ->
            if (latitude == null || longitude == null || item.representativeLat == null || item.representativeLng == null) {
                null
            } else {
                haversineKm(latitude, longitude, item.representativeLat, item.representativeLng)
            }
        }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }
    }

    private fun reverseKeepingUnknownsLast(
        sorted: List<CanyonSearchItem>,
        sortField: SearchSortField,
    ): List<CanyonSearchItem> {
        val (known, unknown) = when (sortField) {
            SearchSortField.INTEREST -> sorted.partition { it.interet != null }
            SearchSortField.DIFFICULTY -> sorted.partition { it.cotationRating.isKnown }
            SearchSortField.ELEVATION -> sorted.partition { it.denivele != null }
            SearchSortField.LENGTH -> sorted.partition { it.longueur != null }
            SearchSortField.MAX_WATERFALL -> sorted.partition { it.cascadeMax != null }
            SearchSortField.DISTANCE -> sorted.partition { it.representativeLat != null && it.representativeLng != null }
            else -> return sorted.reversed()
        }
        return known.reversed() + unknown
    }

    private fun haversineKm(
        latitude: Double,
        longitude: Double,
        targetLatitude: Double,
        targetLongitude: Double,
    ): Double {
        val earthRadiusKm = 6371.0
        val latDistance = Math.toRadians(targetLatitude - latitude)
        val lonDistance = Math.toRadians(targetLongitude - longitude)
        val a = sin(latDistance / 2).pow(2.0) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(targetLatitude)) *
            sin(lonDistance / 2).pow(2.0)
        return 2 * earthRadiusKm * asin(sqrt(a))
    }
}

private fun <T, R : Comparable<R>> compareNullable(
    selector: (T) -> R?,
): Comparator<T> {
    return Comparator { left, right ->
        val leftValue = selector(left)
        val rightValue = selector(right)
        when {
            leftValue == null && rightValue == null -> 0
            leftValue == null -> 1
            rightValue == null -> -1
            else -> leftValue.compareTo(rightValue)
        }
    }
}
