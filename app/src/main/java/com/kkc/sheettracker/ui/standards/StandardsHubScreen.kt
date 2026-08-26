package com.kkc.sheettracker.ui.standards

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.KKCTopAppBar

/**
 * Tiles on the [StandardsHubScreen] grid. Molding Library, Safety / SDS, and Archive are live destinations; Door
 * Profiles and KKC Standards are placeholders reserved for future reference libraries — they
 * render dimmed and inert with a "Coming soon" badge until those screens exist.
 */
enum class StandardsTile(val label: String, val icon: ImageVector, val enabled: Boolean) {
    MOLDING("Molding Library", Icons.Filled.Straighten, enabled = true),
    DOOR_PROFILES("Door Profiles", Icons.Filled.DoorFront, enabled = false),
    KKC_STANDARDS("KKC Standards", Icons.Filled.ClearAll, enabled = false),
    SAFETY("Safety / SDS", Icons.Filled.Shield, enabled = true),
    ARCHIVE("Archive", Icons.Filled.Archive, enabled = true)
}

/**
 * Landing hub for the shop-floor "Standards" reference section: a grid of tiles, one per
 * [StandardsTile]. Purely navigational — no repository, no async data — enabled tiles route to
 * their real screens, disabled tiles are visibly dimmed with a "Coming soon" badge and no-op.
 */
@Composable
fun StandardsHubScreen(
    onBack: () -> Unit,
    onOpenMolding: () -> Unit,
    onOpenSafety: () -> Unit,
    onOpenArchive: () -> Unit,
    safetyNotificationCount: Int = 0
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        KKCTopAppBar(
            title = { Text("Library") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(StandardsTile.entries.toList(), key = { it.name }) { tile ->
                val onClick = when (tile) {
                    StandardsTile.MOLDING -> onOpenMolding
                    StandardsTile.SAFETY -> onOpenSafety
                    StandardsTile.ARCHIVE -> onOpenArchive
                    else -> null
                }
                StandardsTileCard(
                    tile = tile,
                    onClick = onClick,
                    safetyNotificationCount = if (tile == StandardsTile.SAFETY) safetyNotificationCount else 0
                )
            }
        }
    }
}

@Composable
private fun StandardsTileCard(
    tile: StandardsTile,
    onClick: (() -> Unit)?,
    safetyNotificationCount: Int = 0
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val elevation by animateDpAsState(
        targetValue = if (isPressed && tile.enabled) 8.dp else 4.dp,
        label = "cardElevation"
    )
    val cardShape = RoundedCornerShape(16.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .shadow(elevation, cardShape, clip = false)
            .clip(cardShape)
            .border(1.dp, borderColor, cardShape)
            .background(surfaceColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = tile.enabled
            ) {
                if (tile.enabled) onClick?.invoke()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val badgeBg = if (tile.enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val iconTint = if (tile.enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                if (tile == StandardsTile.SAFETY && safetyNotificationCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(safetyNotificationCount.toString())
                            }
                        }
                    ) {
                        Icon(
                            imageVector = tile.icon,
                            contentDescription = tile.label,
                            modifier = Modifier.size(28.dp),
                            tint = iconTint
                        )
                    }
                } else {
                    Icon(
                        imageVector = tile.icon,
                        contentDescription = tile.label,
                        modifier = Modifier.size(28.dp),
                        tint = iconTint
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = tile.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (tile.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                },
                textAlign = TextAlign.Center
            )

            if (!tile.enabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Coming soon",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

