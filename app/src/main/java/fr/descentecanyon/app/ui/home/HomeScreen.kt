package fr.descentecanyon.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.test.TestTags
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCanyonClick: (Int) -> Unit,
    onQuickSearchClick: () -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val donationUrl = stringResource(R.string.support_donation_url)
    var showLoginDialog by remember { mutableStateOf(false) }
    var selectedFeedOverride by remember { mutableStateOf<HomeFeedType?>(null) }
    val selectedFeed = selectedFeedOverride ?: homeState.selectedFeed
    val listState = rememberLazyListState()
    val selectFeed: (HomeFeedType) -> Unit = { type ->
        selectedFeedOverride = type
        homeViewModel.selectFeed(type)
    }

    LaunchedEffect(homeState.selectedFeed, selectedFeedOverride) {
        if (selectedFeedOverride == homeState.selectedFeed) {
            selectedFeedOverride = null
        }
    }

    LaunchedEffect(
        listState,
        selectedFeed,
        homeState.debitGeoFilter.canLoadMore,
        homeState.debitFeed.items.size,
    ) {
        if (selectedFeed != HomeFeedType.DEBITS || !homeState.debitGeoFilter.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            layoutInfo.totalItemsCount > 0 && lastVisibleIndex >= layoutInfo.totalItemsCount - LOAD_MORE_VISIBLE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore) homeViewModel.loadMoreDebits()
            }
    }

    if (showLoginDialog) {
        LoginDialog(
            uiState = authState,
            onUsernameChanged = authViewModel::onUsernameChanged,
            onPasswordChanged = authViewModel::onPasswordChanged,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout,
            onDismiss = { showLoginDialog = false },
        )
    }

    val activeFeedState = when (selectedFeed) {
        HomeFeedType.DEBITS -> homeState.debitFeed
        HomeFeedType.FORUM -> homeState.forumFeed
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = { showLoginDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.user_account),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                QuickSearchCard(onClick = onQuickSearchClick)
            }

            item {
                DonationCard(onClick = { openExternalUrl(context, donationUrl) })
            }

            item {
                HomeFeedPicker(
                    selectedFeed = selectedFeed,
                    onFeedSelected = selectFeed,
                )
            }

            item {
                HomeFeedHeader(
                    selectedFeed = selectedFeed,
                    onRefresh = homeViewModel::refreshSelectedFeed,
                )
            }

            if (selectedFeed == HomeFeedType.DEBITS) {
                item {
                    HomeDebitGeoFilterControls(
                        filterState = homeState.debitGeoFilter,
                        onCountrySelected = homeViewModel::selectDebitCountry,
                        onDepartmentSelected = homeViewModel::selectDebitDepartment,
                        onClear = homeViewModel::clearDebitGeoFilter,
                    )
                }
            }

            if (activeFeedState.notice == HomeFeedNotice.OFFLINE_BANNER ||
                activeFeedState.notice == HomeFeedNotice.STALE_BANNER
            ) {
                item {
                    HomeFeedBanner(
                        selectedFeed = selectedFeed,
                        notice = activeFeedState.notice,
                        lastSyncedAtEpochMs = activeFeedState.lastSyncedAtEpochMs,
                    )
                }
            }

            if (activeFeedState.isLoading && activeFeedState.items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (!activeFeedState.isLoading && activeFeedState.items.isEmpty()) {
                item {
                    HomeEmptyState(
                        selectedFeed = selectedFeed,
                        notice = activeFeedState.notice,
                        onRetry = homeViewModel::refreshSelectedFeed,
                        onShowDebits = { selectFeed(HomeFeedType.DEBITS) },
                        onShowForum = { selectFeed(HomeFeedType.FORUM) },
                        onQuickSearchClick = onQuickSearchClick,
                        onMapClick = onMapClick,
                        hasCachedDebits = homeState.debitFeed.items.isNotEmpty(),
                        hasCachedForum = homeState.forumFeed.items.isNotEmpty(),
                        isDebitFilterActive = homeState.debitGeoFilter.hasActiveFilter(),
                        onClearDebitFilters = homeViewModel::clearDebitGeoFilter,
                    )
                }
            }

            when (selectedFeed) {
                HomeFeedType.DEBITS -> {
                    items(
                        items = homeState.debitFeed.items,
                        key = { latestDebitItemKey(it) },
                    ) { debit ->
                        DebitCard(
                            debit = debit,
                            onClick = { onCanyonClick(debit.canyonId) },
                        )
                    }
                }

                HomeFeedType.FORUM -> {
                    items(
                        items = homeState.forumFeed.items,
                        key = { forumTopicItemKey(it) },
                    ) { topic ->
                        ForumTopicCard(
                            topic = topic,
                            onClick = {
                                val url = topic.lastMessageUrl.ifBlank { topic.topicUrl }
                                openExternalUrl(context, url)
                            },
                        )
                    }
                }
            }

            item {
                CreditCard()
            }
        }
    }
}

private const val LOAD_MORE_VISIBLE_THRESHOLD = 3

@Composable
private fun HomeFeedPicker(
    selectedFeed: HomeFeedType,
    onFeedSelected: (HomeFeedType) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth(),
    ) {
        val options = listOf(HomeFeedType.DEBITS, HomeFeedType.FORUM)
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selectedFeed == option,
                onClick = { onFeedSelected(option) },
                modifier = Modifier.weight(1f),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        if (option == HomeFeedType.DEBITS) {
                            stringResource(R.string.home_feed_debits)
                        } else {
                            stringResource(R.string.home_feed_forum)
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun HomeFeedHeader(
    selectedFeed: HomeFeedType,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedFeed == HomeFeedType.DEBITS) {
                    stringResource(R.string.last_debits)
                } else {
                    stringResource(R.string.home_forum_title)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                )
            }
        }
        Text(
            text = if (selectedFeed == HomeFeedType.DEBITS) {
                stringResource(R.string.home_debits_subtitle)
            } else {
                stringResource(R.string.home_forum_subtitle)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeDebitGeoFilterControls(
    filterState: HomeDebitGeoFilterState,
    onCountrySelected: (String?) -> Unit,
    onDepartmentSelected: (String?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeStringPickerField(
                    label = stringResource(R.string.search_filter_country),
                    selected = filterState.selectedCountry,
                    emptyLabel = stringResource(R.string.search_filter_any_country),
                    options = filterState.availableCountries,
                    enabled = filterState.availableCountries.isNotEmpty(),
                    onSelected = onCountrySelected,
                    modifier = Modifier.weight(1f),
                )
                if (filterState.hasActiveFilter()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.search_clear_filters))
                    }
                }
            }

            if (filterState.selectedCountry != null) {
                HomeStringPickerField(
                    label = stringResource(R.string.search_filter_department),
                    selected = filterState.selectedDepartment,
                    emptyLabel = stringResource(R.string.search_filter_any_department),
                    options = filterState.availableDepartments,
                    enabled = filterState.availableDepartments.isNotEmpty(),
                    onSelected = onDepartmentSelected,
                )
            }
        }
    }
}

@Composable
private fun HomeStringPickerField(
    label: String,
    selected: String?,
    emptyLabel: String,
    options: List<String>,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filteredOptions = remember(options, query) {
        if (query.isBlank()) options else options.filter { it.matchesPickerQuery(query) }
    }

    OutlinedButton(
        onClick = {
            query = ""
            showPicker = true
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = selected ?: emptyLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_filter_option_search)) },
                        singleLine = true,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        item {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = emptyLabel,
                                        color = if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    showPicker = false
                                    onSelected(null)
                                },
                            )
                        }
                        if (filteredOptions.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_filter_option_no_results),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                )
                            }
                        } else {
                            items(filteredOptions) { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option,
                                            color = if (option == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = {
                                        showPicker = false
                                        onSelected(option)
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

private fun String.matchesPickerQuery(query: String): Boolean {
    return normalizedPickerText().contains(query.normalizedPickerText())
}

private fun String.normalizedPickerText(): String {
    return Normalizer
        .normalize(this, Normalizer.Form.NFD)
        .replace(DiacriticsRegex, "")
        .lowercase()
}

private val DiacriticsRegex = Regex("\\p{Mn}+")

@Composable
private fun HomeFeedBanner(
    selectedFeed: HomeFeedType,
    notice: HomeFeedNotice?,
    lastSyncedAtEpochMs: Long?,
    modifier: Modifier = Modifier,
) {
    val title = when (notice) {
        HomeFeedNotice.OFFLINE_BANNER -> stringResource(R.string.home_offline_banner_title)
        HomeFeedNotice.STALE_BANNER -> stringResource(R.string.home_stale_banner_title)
        else -> return
    }
    val body = when (notice) {
        HomeFeedNotice.OFFLINE_BANNER -> if (selectedFeed == HomeFeedType.DEBITS) {
            stringResource(R.string.home_offline_banner_debits_body)
        } else {
            stringResource(R.string.home_offline_banner_forum_body)
        }

        HomeFeedNotice.STALE_BANNER -> if (selectedFeed == HomeFeedType.DEBITS) {
            stringResource(R.string.home_stale_banner_debits_body)
        } else {
            stringResource(R.string.home_stale_banner_forum_body)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (notice == HomeFeedNotice.OFFLINE_BANNER) Icons.Default.WifiOff else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lastSyncedAtEpochMs?.let { syncedAt ->
                    Text(
                        text = stringResource(R.string.home_last_sync, formatHomeSyncTimestamp(syncedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeEmptyState(
    selectedFeed: HomeFeedType,
    notice: HomeFeedNotice?,
    onRetry: () -> Unit,
    onShowDebits: () -> Unit,
    onShowForum: () -> Unit,
    onQuickSearchClick: () -> Unit,
    onMapClick: () -> Unit,
    hasCachedDebits: Boolean,
    hasCachedForum: Boolean,
    isDebitFilterActive: Boolean,
    onClearDebitFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (selectedFeed) {
        HomeFeedType.DEBITS -> when (notice) {
            HomeFeedNotice.OFFLINE_EMPTY -> HomeStatusCard(
                icon = Icons.Default.WifiOff,
                title = stringResource(R.string.home_offline_title),
                body = stringResource(R.string.home_offline_body),
                primaryActionLabel = if (hasCachedForum) stringResource(R.string.home_show_forum) else stringResource(R.string.quick_search_title),
                onPrimaryAction = if (hasCachedForum) onShowForum else onQuickSearchClick,
                secondaryActionLabel = if (hasCachedForum) stringResource(R.string.retry) else stringResource(R.string.home_open_map),
                onSecondaryAction = if (hasCachedForum) onRetry else onMapClick,
                modifier = modifier,
            )

            HomeFeedNotice.SERVICE_UNAVAILABLE -> HomeStatusCard(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.home_service_unavailable_title),
                body = stringResource(R.string.home_service_unavailable_body),
                primaryActionLabel = stringResource(R.string.retry),
                onPrimaryAction = onRetry,
                secondaryActionLabel = hasCachedForum.takeIf { it }?.let { stringResource(R.string.home_show_forum) },
                onSecondaryAction = hasCachedForum.takeIf { it }?.let { onShowForum },
                modifier = modifier,
            )

            null -> if (isDebitFilterActive) {
                HomeStatusCard(
                    icon = Icons.Default.FilterList,
                    title = stringResource(R.string.home_debit_filter_no_results_title),
                    body = stringResource(R.string.home_debit_filter_no_results_body),
                    primaryActionLabel = stringResource(R.string.search_clear_filters),
                    onPrimaryAction = onClearDebitFilters,
                    modifier = modifier,
                )
            }

            else -> Unit
        }

        HomeFeedType.FORUM -> when (notice) {
            HomeFeedNotice.OFFLINE_EMPTY -> HomeStatusCard(
                icon = Icons.Default.WifiOff,
                title = stringResource(R.string.home_forum_offline_title),
                body = stringResource(R.string.home_forum_offline_body),
                primaryActionLabel = if (hasCachedDebits) stringResource(R.string.home_show_debits) else stringResource(R.string.quick_search_title),
                onPrimaryAction = if (hasCachedDebits) onShowDebits else onQuickSearchClick,
                secondaryActionLabel = stringResource(R.string.home_open_map),
                onSecondaryAction = onMapClick,
                modifier = modifier,
            )

            HomeFeedNotice.SERVICE_UNAVAILABLE -> HomeStatusCard(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.home_forum_service_unavailable_title),
                body = stringResource(R.string.home_forum_service_unavailable_body),
                primaryActionLabel = stringResource(R.string.retry),
                onPrimaryAction = onRetry,
                secondaryActionLabel = hasCachedDebits.takeIf { it }?.let { stringResource(R.string.home_show_debits) },
                onSecondaryAction = hasCachedDebits.takeIf { it }?.let { onShowDebits },
                modifier = modifier,
            )

            else -> Unit
        }
    }
}

@Composable
private fun HomeStatusCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPrimaryAction) {
                Text(primaryActionLabel)
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

internal fun latestDebitItemKey(debit: Debit): String = buildString {
    append(debit.canyonId)
    append('-')
    append(debit.date)
    append('-')
    append(debit.auteur.orEmpty())
}

internal fun forumTopicItemKey(topic: ForumActiveTopic): String = buildString {
    append(topic.topicId)
    append('-')
    append(topic.lastPostedAtEpochMs ?: topic.lastPostedAtText)
}

@Composable
private fun QuickSearchCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeActionCard(
        title = stringResource(R.string.quick_search_title),
        hint = stringResource(R.string.quick_search_hint),
        icon = Icons.Default.Search,
        onClick = onClick,
        modifier = modifier.testTag(TestTags.homeQuickSearch),
    )
}

@Composable
private fun DonationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeActionCard(
        title = stringResource(R.string.support_donation_title),
        hint = stringResource(R.string.support_donation_hint),
        icon = Icons.Default.Favorite,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HomeActionCard(
    title: String,
    hint: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CreditCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Text(
            text = stringResource(R.string.credit_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun DebitCard(
    debit: Debit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = debitLevelColor(debit.niveau)
    val isCrue = debit.niveau == NiveauDebit.CRUE

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isCrue) 8.dp else 6.dp)
                    .background(if (isCrue) bgColor else bgColor.copy(alpha = 0.9f)),
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = debit.canyonNom?.takeIf { it.isNotBlank() } ?: "Canyon #${debit.canyonId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DebitBadge(niveau = debit.niveau)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = debit.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    debit.auteur?.let { auteur ->
                        Text(
                            text = auteur,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                debit.commentaire?.takeIf { it.isNotBlank() }?.let { comment ->
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ForumTopicCard(
    topic: ForumActiveTopic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildForumTopicMeta(topic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.home_forum_open_last_message),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = topic.lastAuthor
                    ?.takeIf { it.isNotBlank() }
                    ?.let { author -> stringResource(R.string.home_forum_last_message_by, author, topic.lastPostedAtText) }
                    ?: topic.lastPostedAtText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun buildForumTopicMeta(topic: ForumActiveTopic): String {
    return buildString {
        append(topic.forumName)
        append(" • ")
        append(topic.replyCount)
        append(" rép.")
        append(" • ")
        append(formatCompactCount(topic.viewCount))
        append(" vues")
    }
}

private fun formatCompactCount(value: Int): String {
    return when {
        value >= 10_000 -> "${value / 1_000}k"
        value >= 1_000 -> String.format("%.1fk", value / 1_000f)
        else -> value.toString()
    }
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun formatHomeSyncTimestamp(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
