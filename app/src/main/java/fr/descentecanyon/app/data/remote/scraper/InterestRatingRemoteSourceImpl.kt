package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InterestRatingRemoteSourceImpl @Inject constructor(
    private val scraper: CanyonScraper,
) : InterestRatingRemoteSource {
    override suspend fun get(canyonId: Int): Result<CanyonInterestRating> {
        return scraper.getInterestRating(canyonId)
    }

    override suspend fun submit(submission: InterestRatingSubmission): Result<Unit> {
        return scraper.submitInterestRating(submission)
    }
}
