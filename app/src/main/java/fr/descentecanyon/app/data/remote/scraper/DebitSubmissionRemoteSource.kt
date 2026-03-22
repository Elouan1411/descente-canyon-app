package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.DebitSubmission

interface DebitSubmissionRemoteSource {
    suspend fun submit(submission: DebitSubmission): Result<Unit>
}
