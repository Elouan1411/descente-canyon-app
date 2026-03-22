package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
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
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val url: String,
    val thumbnailUrl: String? = null,
    val auteur: String? = null,
    val description: String? = null,
    val localPath: String? = null,
)
