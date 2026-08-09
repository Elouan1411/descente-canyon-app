package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "canyon_pdfs",
    foreignKeys = [
        ForeignKey(
            entity = CanyonEntity::class,
            parentColumns = ["id"],
            childColumns = ["canyonId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["canyonId"]),
        Index(value = ["serverPdfId"], unique = true),
    ]
)
data class CanyonPdfEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serverPdfId: String,
    val canyonId: Int,
    val fileName: String,
    val fileSize: Long,
    val localPath: String?,
    val remoteUrl: String,
    val uploadedAt: Long,
    val mimeType: String = "application/pdf",
    val isDownloaded: Boolean = false,
)
