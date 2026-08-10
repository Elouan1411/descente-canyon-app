package fr.descentecanyon.app.ui.canyon

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.descentecanyon.app.R
import fr.descentecanyon.app.data.local.entity.CanyonPdfEntity
import fr.descentecanyon.app.data.repository.CanyonPdfRepository
import fr.descentecanyon.app.security.InstallationIdManager
import fr.descentecanyon.app.ui.design.DcColors
import fr.descentecanyon.app.ui.design.LocalDcColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CanyonPdfSection(
    canyonId: Int,
    pdfRepository: CanyonPdfRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalDcColors.current
    val currentInstallationId = remember(context) { InstallationIdManager.getInstallationId(context) }

    val pdfList by pdfRepository.getPdfsForCanyon(canyonId).collectAsState(initial = emptyList())

    var isExpanded by remember { mutableStateOf(true) }
    var isUploading by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var pdfToDelete by remember { mutableStateOf<CanyonPdfEntity?>(null) }
    var isDeleting by remember { mutableStateOf(false) }

    LaunchedEffect(canyonId) {
        isSyncing = true
        pdfRepository.syncPdfsForCanyon(context, canyonId)
        isSyncing = false
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        val fileDetails = getFileDetails(context, uri) ?: return@rememberLauncherForActivityResult
        val mimeType = context.contentResolver.getType(uri) ?: "application/pdf"
        
        val allowedTypes = listOf("application/pdf", "image/jpeg", "image/png", "image/webp", "application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")
        if (!allowedTypes.contains(mimeType) && !fileDetails.first.endsWith(".gpx", ignoreCase = true)) {
            Toast.makeText(context, "Format non supporté.", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        
        val maxSizeBytes = 100L * 1024L * 1024L

        if (fileDetails.second > maxSizeBytes) {
            Toast.makeText(
                context,
                context.getString(R.string.pdf_error_limit_100mb),
                Toast.LENGTH_LONG
            ).show()
            return@rememberLauncherForActivityResult
        }

        isUploading = true
        scope.launch {
            val result = pdfRepository.uploadPdf(
                context = context,
                canyonId = canyonId,
                fileUri = uri,
                fileName = fileDetails.first,
                fileSize = fileDetails.second
            )
            isUploading = false
            if (result.isSuccess) {
                Toast.makeText(
                    context,
                    context.getString(R.string.pdf_upload_success),
                    Toast.LENGTH_SHORT
                ).show()
                pdfRepository.syncPdfsForCanyon(context, canyonId)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.pdf_upload_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    if (pdfToDelete != null) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pdfToDelete = null },
            title = { Text("Supprimer le document", fontWeight = FontWeight.Bold) },
            text = { Text("Voulez-vous vraiment supprimer '${pdfToDelete?.fileName}' ? Cette action est définitive.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pdfToDelete ?: return@Button
                        isDeleting = true
                        scope.launch {
                            val res = pdfRepository.deletePdf(context, target)
                            isDeleting = false
                            pdfToDelete = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "Document supprimé.", Toast.LENGTH_SHORT).show()
                                pdfRepository.syncPdfsForCanyon(context, canyonId)
                            } else {
                                Toast.makeText(context, "Erreur lors de la suppression.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onError, strokeWidth = 2.dp)
                    } else {
                        Text("Supprimer")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pdfToDelete = null }, enabled = !isDeleting) {
                    Text("Annuler")
                }
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceBase,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.pdf_community_documents),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (pdfList.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = pdfList.size.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            isSyncing = true
                            scope.launch {
                                pdfRepository.syncPdfsForCanyon(context, canyonId)
                                isSyncing = false
                            }
                        },
                        enabled = !isSyncing && !isUploading,
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.pdf_sync),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = { pdfPickerLauncher.launch("*/*") },
                        enabled = !isUploading && !isSyncing,
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.pdf_add_button),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = colors.textSecondary,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pdfList.isEmpty()) {
                        Text(
                            text = stringResource(R.string.pdf_no_documents),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        pdfList.forEach { pdf ->
                            val isMyUpload = !pdf.uploaderId.isNullOrBlank() && pdf.uploaderId == currentInstallationId
                            PdfItemRow(
                                pdf = pdf,
                                isMyUpload = isMyUpload,
                                onOpenExternal = {
                                    if (pdf.isDownloaded) {
                                        pdfRepository.openPdfWithExternalApp(context, pdf)
                                    } else {
                                        scope.launch {
                                            val dlResult = pdfRepository.downloadPdfFile(context, pdf)
                                            if (dlResult.isSuccess) {
                                                pdfRepository.openPdfWithExternalApp(context, pdf)
                                            }
                                        }
                                    }
                                },
                                onDownloadClick = {
                                    scope.launch {
                                        pdfRepository.downloadPdfFile(context, pdf)
                                    }
                                },
                                onDeleteClick = {
                                    pdfToDelete = pdf
                                },
                                colors = colors,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfItemRow(
    pdf: CanyonPdfEntity,
    isMyUpload: Boolean,
    onOpenExternal: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    colors: DcColors,
) {
    var isItemExpanded by remember { mutableStateOf(false) }
    val sizeMb = String.format(Locale.ROOT, "%.1f MB", pdf.fileSize / (1024.0 * 1024.0))
    val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(pdf.uploadedAt))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isItemExpanded = !isItemExpanded },
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceRaised,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.borderSubtle),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = when {
                            pdf.mimeType.startsWith("image/") -> Icons.Default.Image
                            pdf.mimeType.contains("gpx") || pdf.fileName.endsWith(".gpx", ignoreCase = true) -> Icons.Default.Map
                            else -> Icons.Default.PictureAsPdf
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pdf.fileName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Text(
                            text = "$sizeMb • $dateStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )

                        Text(
                            text = if (pdf.isDownloaded) {
                                stringResource(R.string.pdf_downloaded_tag)
                            } else {
                                stringResource(R.string.pdf_online_tag)
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = if (pdf.isDownloaded) MaterialTheme.colorScheme.primary else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isMyUpload) {
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Supprimer mon document",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            )
                        }
                    }

                    if (pdf.isDownloaded) {
                        IconButton(onClick = onOpenExternal) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.pdf_open),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(onClick = onDownloadClick) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.pdf_download),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    IconButton(onClick = { isItemExpanded = !isItemExpanded }) {
                        Icon(
                            imageVector = if (isItemExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = colors.textSecondary,
                        )
                    }
                }
            }  }

            AnimatedVisibility(visible = isItemExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    if (!pdf.isDownloaded || pdf.localPath == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceBase),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(onClick = onDownloadClick) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Télécharger pour afficher le document")
                            }
                        }
                    } else {
                        val file = java.io.File(pdf.localPath)
                        if (pdf.mimeType.startsWith("image/")) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = (-12).dp)
                                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                    .background(colors.surfaceBase),
                                contentAlignment = Alignment.Center
                            ) {
                                coil3.compose.AsyncImage(
                                    model = Uri.fromFile(file),
                                    contentDescription = pdf.fileName,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 650.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                                )
                            }
                        } else if (pdf.mimeType == "application/pdf") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = (-12).dp)
                                    .height(550.dp)
                                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                    .background(colors.surfaceBase)
                            ) {
                                PdfViewer(file = file)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceBase),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Tracé GPX - Utilisez le bouton ci-dessus pour l'ouvrir dans votre application GPS.",
                                    color = colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getFileDetails(context: Context, uri: Uri): Pair<String, Long>? {
    return try {
        var name = "document.pdf"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        Pair(name, size)
    } catch (e: Exception) {
        null
    }
}
