package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import javax.inject.Inject

class SyncPendingDebitsUseCase @Inject constructor(
    private val repository: DebitSubmissionRepository,
) {
    suspend operator fun invoke(): Result<Int> = repository.syncPending()
}
