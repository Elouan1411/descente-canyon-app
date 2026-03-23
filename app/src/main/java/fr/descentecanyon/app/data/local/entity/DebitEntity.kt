package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debits",
    foreignKeys = [
        ForeignKey(
            entity = CanyonEntity::class,
            parentColumns = ["id"],
            childColumns = ["canyonId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("canyonId")],
)
data class DebitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val date: String, // ISO date string (yyyy-MM-dd)
    val niveau: String, // NiveauDebit name
    val auteur: String? = null,
    val isDescended: Boolean? = null,
    val waterTemperature: String? = null,
    val airTemperature: String? = null,
    val commentaire: String? = null,
)
