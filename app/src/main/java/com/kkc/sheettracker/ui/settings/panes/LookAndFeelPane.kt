package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.ui.settings.MAX_FIELD_WIDTH
import com.kkc.sheettracker.ui.settings.SettingToggle
import com.kkc.sheettracker.ui.settings.customSwatchThemes
import com.kkc.sheettracker.ui.settings.filterThemesByQuery
import com.kkc.sheettracker.ui.settings.footballTeamThemes
import com.kkc.sheettracker.ui.settings.nflCardLabel
import com.kkc.sheettracker.ui.settings.selectedThemeId
import com.kkc.sheettracker.ui.settings.settingsFieldColors
import com.kkc.sheettracker.ui.settings.themeSwatch
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun LookAndFeelPane(
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pick: (String) -> Unit = { id ->
        onThemeFollowSyncedDefaultChanged(false)
        onThemeOverrideChanged(id)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Brightness") {
            SettingToggle(
                label = "Follow system theme",
                checked = followSystemTheme,
                onCheckedChange = onFollowSystemThemeChanged,
                subtitle = "Match the tablet's light/dark setting"
            )
            if (!followSystemTheme) {
                GroupDivider()
                SettingToggle(label = "Dark mode", checked = darkThemeOverride, onCheckedChange = onThemeChanged)
            }
        }

        GroupCard(caption = "Theme") {
            var nflSearch by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            val searchFocus = remember { FocusRequester() }
            val searchInView = remember { BringIntoViewRequester() }
            val selectedId = selectedThemeId(themeCatalog)
            val custom = remember(themeCatalog) { customSwatchThemes(themeCatalog) }
            val nfl = remember(themeCatalog) { footballTeamThemes(themeCatalog.themes) }
            val activeNfl = nfl.firstOrNull { it.id == selectedId }

            LaunchedEffect(nflSearch) {
                if (nflSearch) {
                    withFrameNanos { } // let the search field attach before focusing it
                    searchFocus.requestFocus()
                    searchInView.bringIntoView()
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                custom.forEach { theme ->
                    ThemeSwatchCard(
                        name = theme.name,
                        colors = themeSwatch(theme, isDarkTheme),
                        selected = theme.id == selectedId,
                        onClick = { pick(theme.id) }
                    )
                }
                if (nfl.isNotEmpty()) {
                    // Opens the team search rather than picking a theme, so it's a button, not a radio.
                    ThemeSwatchCard(
                        name = nflCardLabel(themeCatalog),
                        colors = activeNfl?.let { themeSwatch(it, isDarkTheme) } ?: (scheme.outline to scheme.outlineVariant),
                        selected = activeNfl != null,
                        role = Role.Button,
                        onClick = { nflSearch = true }
                    )
                }
            }

            if (nflSearch) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(searchInView)
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search NFL team") },
                        supportingText = { Text("Applies only to this tablet") },
                        colors = settingsFieldColors(),
                        modifier = Modifier
                            .widthIn(max = MAX_FIELD_WIDTH)
                            .fillMaxWidth()
                            .focusRequester(searchFocus),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    val matches = filterThemesByQuery(nfl, query)
                    if (matches.isEmpty()) {
                        Text("No matching teams", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    matches.forEach { team ->
                        val (primary, secondary) = themeSwatch(team, isDarkTheme)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) {
                                    pick(team.id)
                                    nflSearch = false
                                    query = ""
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(primary))
                            Box(Modifier.size(14.dp).clip(CircleShape).background(secondary))
                            Text(team.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    TextButton(onClick = { nflSearch = false; query = "" }) { Text("Cancel") }
                }
            }

            GroupDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (themeCatalog.overrideThemeId == null) "Using fleet default" else "Applies only to this tablet",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onThemeCatalogReload, shape = RoundedCornerShape(8.dp)) {
                    Text("Reload themes")
                }
                Button(
                    onClick = {
                        onThemeOverrideChanged(null)
                        onThemeFollowSyncedDefaultChanged(true)
                    },
                    enabled = themeCatalog.overrideThemeId != null,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Use fleet default")
                }
            }
        }

        val problems = themeCatalog.loadMessages +
            themeCatalog.invalidThemes.map { "${it.filename}: ${it.message}" }
        problems.forEach { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
    }
}

@Composable
private fun ThemeSwatchCard(
    name: String,
    colors: Pair<Color, Color>,
    selected: Boolean,
    onClick: () -> Unit,
    role: Role = Role.RadioButton,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(shape)
            .border(2.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp))) {
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.first))
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.second))
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            // Two lines so team names like "Indianapolis Colts" aren't cut off in a 112dp card.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
