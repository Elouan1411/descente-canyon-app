package fr.descentecanyon.app.data.local.dao

import fr.descentecanyon.app.data.local.entity.CanyonEntity

private const val MAX_IDS_PER_QUERY = 900

suspend fun CanyonDao.getByIdsChunked(ids: Collection<Int>): List<CanyonEntity> {
    if (ids.isEmpty()) return emptyList()

    val results = mutableListOf<CanyonEntity>()
    ids.distinct()
        .chunked(MAX_IDS_PER_QUERY)
        .forEach { chunk ->
            results += getByIds(chunk)
        }
    return results
}
