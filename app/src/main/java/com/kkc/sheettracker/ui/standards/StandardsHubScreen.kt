package com.kkc.sheettracker.ui.standards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.KKCTopAppBar

/**
 * Tiles on the [StandardsHubScreen] grid. Molding and Safety are live destinations today;
 * Door profiles and KKC standards are placeholders reserved for future reference libraries —
 * they render dimmed and inert until those screens exist.
 */
enum class StandardsTile(val label: String, val icon: ImageVector, val enabled: Boolean) {
    MOLDING("Molding", Icons.Filled.Straighten, enabled = true),
    DOOR_PROFILES("Door profiles", Icons.Filled.DoorFront, enabled = false),
    KKC_STANDARDS("KKC standards", Icons.Filled.ClearAll, enabled = false),
    SAFETY("Safety / SDS", Icons.Filled.Shield, enabled = true)
}

/**
 * Landing hub for the shop-floor "Standards" reference section: a static grid of tiles, one per
 * [StandardsTile]. Purely navigational — no repository, no async data — enabled tiles route to
 * their real screens, disabled tiles are visibly dimmed with a "Coming soon" badge and no-op.
 */
@Composable
fun StandardsHubScreen(
    onBack: () -> Unit,
    onOpenMolding: () -> Unit,
    onOpenSafety: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        KKCTopAppBar(
            title = { Text("Standards") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(StandardsTile.entries.toList(), key = { it.name }) { tile ->
                val onClick = when (tile) {
                    StandardsTile.MOLDING -> onOpenMolding
                    StandardsTile.SAFETY -> onOpenSafety
                    else -> null
                }
                StandardsTileCard(tile = tile, onClick = onClick)
            }
        }
    }
}

@Composable
private fun StandardsTileCard(
    tile: StandardsTile,
    onClick: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .clickable(enabled = tile.enabled) { if (tile.enabled) onClick?.invoke() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .alpha(if (tile.enabled) 1f else 0.45f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = tile.label,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = tile.label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (!tile.enabled) {
                Text(
                    text = "Coming soon",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
