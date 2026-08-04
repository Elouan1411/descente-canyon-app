package fr.descentecanyon.app.ui.users

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcMetricTile
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding

@Composable
fun UserSearchScreen(
    onBackClick: () -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: UserSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(title = "Utilisateurs", navigation = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            })
        },
        modifier = modifier,
    ) { padding ->
        val contentWidth = rememberDcContentWidth()
        val horizontalPadding = rememberDcScreenHorizontalPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).width(contentWidth).padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 20.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Rechercher un utilisateur") },
                )
            }
            if (state.query.trim().length < 2) {
                item { DcEmptyState(title = "Recherchez un pseudo", body = "Les utilisateurs sont issus des observations de débit et du forum.", icon = Icons.Default.Person) }
            } else if (state.users.isEmpty()) {
                item { DcEmptyState(title = "Aucun utilisateur", body = "Aucun pseudo ne correspond à cette recherche.", icon = Icons.Default.Search) }
            } else {
                items(state.users, key = { it.username }) { user ->
                    UserResultCard(
                        user = user,
                        isFollowed = user.normalizedUsername in state.followedUsernames,
                        onClick = { onUserClick(user.normalizedUsername) },
                        onToggleFollow = { viewModel.toggleFollow(user) },
                    )
                }
            }
        }
    }
}

@Composable
fun UserProfileScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val user = state.user
    val context = LocalContext.current
    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(title = user?.username ?: "Utilisateur", navigation = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            })
        },
        modifier = modifier,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (user == null) {
                DcEmptyState(title = "Utilisateur introuvable", body = "Cet utilisateur n’est plus disponible dans la base locale.", icon = Icons.Default.Person)
            } else {
                val contentWidth = rememberDcContentWidth()
                val horizontalPadding = rememberDcScreenHorizontalPadding()
                LazyColumn(
                    modifier = Modifier.width(contentWidth).padding(horizontal = horizontalPadding),
                    contentPadding = PaddingValues(top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        DcCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(user.username, fontWeight = FontWeight.Bold)
                                    Text(
                                        listOfNotNull(
                                            if (user.hasDebitActivity) "Débits" else null,
                                            if (user.hasForumActivity) "Forum" else null,
                                        ).joinToString(" • "),
                                    )
                                }
                                Button(onClick = viewModel::toggleFollow) {
                                    Icon(Icons.Default.Notifications, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (state.isFollowed) "Suivi" else "Suivre")
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DcMetricTile("Débits", user.debitObservationCount.toString(), Modifier.weight(1f))
                            DcMetricTile("Posts", user.forumPostCount.toString(), Modifier.weight(1f))
                        }
                    }
                    item { Text("Activité récente", fontWeight = FontWeight.Bold) }
                    user.lastDebitObservationAt?.let { date ->
                        item { ActivitySummaryCard("Dernier débit", date, user.lastDebitObservationUrl) }
                    }
                    user.lastForumPostAt?.let { date ->
                        item { ActivitySummaryCard("Dernier post forum", date, user.lastForumPostUrl) }
                    }
                    user.profileUrl?.let { profileUrl ->
                        item {
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl)))
                            }) { Text("Ouvrir le profil forum") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun UserResultCard(
    user: ForumUser,
    isFollowed: Boolean,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    DcCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.username, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(if (user.hasDebitActivity) "Débits ${user.debitObservationCount}" else null, if (user.hasForumActivity) "Forum ${user.forumPostCount}" else null).joinToString(" • "))
            }
            TextButton(onClick = onToggleFollow) { Text(if (isFollowed) "Suivi" else "Suivre") }
        }
    }
}

@Composable
private fun ActivitySummaryCard(label: String, date: String, url: String?) {
    val context = LocalContext.current
    DcCard(onClick = if (url == null) null else ({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(date)
    }
}
