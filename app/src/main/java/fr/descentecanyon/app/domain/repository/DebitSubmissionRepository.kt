package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import kotlinx.coroutines.flow.Flow

interface DebitSubmissionRepository {
    suspend fun submit(submission: DebitSubmission): Result<DebitSubmissionStatus>
    suspend fun syncPending(): Result<Int>
    fun observePendingCount(): Flow<Int>
}
