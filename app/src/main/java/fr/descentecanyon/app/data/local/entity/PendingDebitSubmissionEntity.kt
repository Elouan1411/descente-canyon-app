package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_debit_submissions")
data class PendingDebitSubmissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val observerName: String,
    val observerEmail: String?,
    val observationDate: String,
    val isDescended: Boolean,
    val debitLevel: String,
    val waterTemperature: String,
    val airTemperature: String,
    val comment: String,
    val createdAt: Long = System.currentTimeMillis(),
)
