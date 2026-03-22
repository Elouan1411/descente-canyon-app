package fr.descentecanyon.app.data.network

import kotlinx.coroutines.flow.Flow

interface ConnectivityObserver {
    fun observe(): Flow<Boolean>
    fun isCurrentlyOnline(): Boolean
}
