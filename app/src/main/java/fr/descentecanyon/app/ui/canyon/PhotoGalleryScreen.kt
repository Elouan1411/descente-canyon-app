package fr.descentecanyon.app.ui.canyon

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.descentecanyon.app.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGalleryScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoGalleryViewModel = hiltViewModel(),
) {
    val photos by PhotoGallerySession.photos.collectAsStateWithLifecycle()
    val initialIndex by PhotoGallerySession.initialIndex.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showOverlay by rememberSaveable { mutableStateOf(true) }

    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { photos.size },
    )

    DisposableEffect(context) {
        val activity = context as? Activity
        val window = activity?.window
        val previousStatusBarColor = window?.statusBarColor
        val previousNavBarColor = window?.navigationBarColor
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightStatus = insetsController?.isAppearanceLightStatusBars
        val previousLightNav = insetsController?.isAppearanceLightNavigationBars

        if (window != null && insetsController != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = AndroidColor.BLACK
            window.navigationBarColor = AndroidColor.BLACK
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            PhotoGallerySession.clear()
            if (window != null && insetsController != null) {
                previousStatusBarColor?.let { window.statusBarColor = it }
                previousNavBarColor?.let { window.navigationBarColor = it }
                previousLightStatus?.let { insetsController.isAppearanceLightStatusBars = it }
                previousLightNav?.let { insetsController.isAppearanceLightNavigationBars = it }
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showOverlay = !showOverlay },
            ) {
                AsyncImage(
                    model = photo.localPath ?: photo.url,
                    contentDescription = photo.description,
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentScale = ContentScale.Fit,
                )

                AnimatedVisibility(
                    visible = showOverlay,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${page + 1}/${photos.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (photo.localPath == null) {
                                IconButton(
                                    onClick = { viewModel.downloadPhoto(photo.id) },
                                    enabled = photo.id != 0L && !uiState.downloadingPhotoIds.contains(photo.id),
                                ) {
                                    if (uiState.downloadingPhotoIds.contains(photo.id)) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = stringResource(R.string.photo_download_action),
                                            tint = Color.White,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showOverlay,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        photo.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        photo.auteur?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
