package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary

interface MapIndexRemoteSource {
    suspend fun getMapIndex(): Result<List<ScrapedCanyonSummary>>
}
