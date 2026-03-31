package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions

interface DebitPredictionRepository {
    suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions>
}
