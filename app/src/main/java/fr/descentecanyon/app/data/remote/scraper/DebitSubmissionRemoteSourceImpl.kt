package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.DebitSubmission
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebitSubmissionRemoteSourceImpl @Inject constructor(
    private val scraper: CanyonScraper,
) : DebitSubmissionRemoteSource {
    override suspend fun submit(submission: DebitSubmission): Result<Unit> {
        return scraper.submitDebit(submission)
    }
}
