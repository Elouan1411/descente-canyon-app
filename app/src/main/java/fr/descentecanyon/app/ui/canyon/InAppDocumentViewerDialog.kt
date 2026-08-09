package fr.descentecanyon.app.ui.canyon

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import fr.descentecanyon.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppDocumentViewerDialog(
    file: File,
    mimeType: String,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(file.name, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenExternal) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open in external app", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        titleContentColor = Color.White
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    if (mimeType.startsWith("image/")) {
                        ImageViewer(file = file)
                    } else if (mimeType == "application/pdf") {
                        PdfViewer(file = file)
                    } else {
                        // Fallback text
                        Text(
                            text = "Format non supporté pour la prévisualisation.",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageViewer(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = max(1f, scale * zoom)
                    val maxX = (size.width * (scale - 1)) / 2
                    val maxY = (size.height * (scale - 1)) / 2
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                }
            }
    ) {
        AsyncImage(
            model = Uri.fromFile(file),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

@Composable
fun PdfViewer(file: File) {
    val context = LocalContext.current
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    
    // Scale tracking for the entire column
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(file) {
        val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fileDescriptor)
        pdfRenderer = renderer
        pageCount = renderer.pageCount

        onDispose {
            renderer.close()
            fileDescriptor.close()
        }
    }

    if (pdfRenderer != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = max(1f, scale * zoom)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(pageCount) { index ->
                    PdfPage(pdfRenderer = pdfRenderer!!, pageIndex = index)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun PdfPage(pdfRenderer: PdfRenderer, pageIndex: Int) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current.density

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            val page = pdfRenderer.openPage(pageIndex)
            val width = (page.width * density * 1.5f).toInt()
            val height = (page.height * density * 1.5f).toInt()
            val createdBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Fill with white background
            createdBitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(createdBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap = createdBitmap
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Page $pageIndex",
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
