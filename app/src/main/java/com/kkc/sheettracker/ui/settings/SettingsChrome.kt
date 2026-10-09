package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.navigation.displayName
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.kkcCardDepth
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(12.dp)
private val RailItemShape = RoundedCornerShape(9.dp)
private const val SAVED_FLASH_MS = 1600L

@Composable
internal fun settingsFieldColors(): TextFieldColors {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    return OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = containerColor,
        unfocusedBorderColor = Color.Transparent,
        focusedContainerColor = containerColor,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
    )
}

// ── Status chips ────────────────────────────────────────────────────────────────

@Composable
internal fun syncDotColor(tone: ChipTone): Color {
    val status = LocalKKCStatusColors.current
    return when (tone) {
        ChipTone.OK -> status.complete
        ChipTone.WARN -> status.skip
        ChipTone.BAD -> status.bad
    }
}

@Composable
internal fun StatusChip(
    label: String,
    onClick: () -> Unit,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dot: Color? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            // 48dp touch target around the slimmer pill; the top bar has the height for it.
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1
        )
    }
}

// ── Work mode row ───────────────────────────────────────────────────────────────

@Composable
internal fun WorkModeRow(
    workMode: WorkMode,
    onWorkModeChanged: (WorkMode) -> Unit,
    flexibleModeEnabled: Boolean,
    onFlexibleModeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupCaption("Work mode", Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Flexible mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Mode switcher on Dashboard and Jobs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = flexibleModeEnabled, onCheckedChange = onFlexibleModeChanged)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val count = WorkMode.entries.size
            val tileWidth = (maxWidth - gap * (count - 1)) / count
            val tileHeight = modeTileHeight(tileWidth)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                WorkMode.entries.forEach { mode ->
                    ModeTile(
                        mode = mode,
                        selected = mode == workMode,
                        onClick = { onWorkModeChanged(mode) },
                        modifier = Modifier.width(tileWidth).height(tileHeight)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTile(mode: WorkMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primary else scheme.surface
    val content = if (selected) scheme.onPrimary else scheme.onSurface
    val logo = workModeLogo(mode)
    // Low-end mode: kkcCardDepth already draws the hairline border in place of the shadow.
    val ownBorder = !selected && !LocalLowEndMode.current.shadowsDisabled
    Column(
        modifier = modifier
            .kkcCardDepth(CardShape, elevation = if (selected) 0.dp else 2.dp)
            .background(container)
            .then(if (ownBorder) Modifier.border(1.dp, scheme.outlineVariant, CardShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = logo.painter,
            contentDescription = null,
            colorFilter = if (logo.tintable) ColorFilter.tint(if (selected) content else scheme.primary) else null,
            modifier = Modifier.fillMaxHeight(0.52f).aspectRatio(1f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            mode.displayName().uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Rail ────────────────────────────────────────────────────────────────────────

@Composable
internal fun SettingsRail(
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    updatesBadge: Int,
    bottomClearance: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(240.dp)
            .verticalScroll(rememberScrollState())
            // Inside the scroll container: verticalScroll clips to its bounds, so the card's
            // shadow needs this inset to render instead of being cut at the viewport edge.
            .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = bottomClearance)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .kkcCardDepth(CardShape, elevation = 2.dp)
                .background(MaterialTheme.colorScheme.surface)
                .selectableGroup()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsSection.entries.forEachIndexed { index, section ->
                val firstAdvanced = section.isAdvanced && index > 0 && !SettingsSection.entries[index - 1].isAdvanced
                if (firstAdvanced) {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    GroupCaption("Advanced", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
                }
                RailItem(
                    section = section,
                    selected = section == selected,
                    badge = if (section == SettingsSection.UPDATES_ABOUT) updatesBadge else 0,
                    onClick = { onSelect(section) }
                )
            }
        }
    }
}

@Composable
private fun RailItem(section: SettingsSection, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content = when {
        selected -> scheme.onSecondaryContainer
        section.isAdvanced -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RailItemShape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(section.icon(selected), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        Text(
            section.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge > 0) {
            val badgeColor = LocalKKCStatusColors.current.skipBg
            // Circle for one digit, pill for more.
            Box(
                modifier = Modifier
                    .heightIn(min = 20.dp)
                    .widthIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$badge",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = badgeContentColor(badgeColor),
                    maxLines = 1
                )
            }
        }
    }
}

// ── Pane building blocks ────────────────────────────────────────────────────────

@Composable
internal fun SectionHeader(section: SettingsSection) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier.size(48.dp).clip(CardShape).background(scheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                section.icon(selected = true),
                contentDescription = null,
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }
        Column {
            Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(section.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GroupCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun GroupCard(
    caption: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (caption != null) GroupCaption(caption, Modifier.padding(start = 4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .kkcCardDepth(CardShape, elevation = 2.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun CardBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun SettingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SettingNavRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Save pattern ────────────────────────────────────────────────────────────────

/** "Saved" confirmation. Every [show] restarts the timer, so a second save gets the full flash. */
internal class SavedFlashState {
    var visible by mutableStateOf(false)
        private set
    internal var shows by mutableIntStateOf(0)
        private set

    fun show() {
        visible = true
        shows++
    }

    internal fun hide() {
        visible = false
    }
}

@Composable
internal fun rememberSavedFlash(): SavedFlashState {
    val flash = remember { SavedFlashState() }
    LaunchedEffect(flash.shows) {
        if (flash.visible) {
            delay(SAVED_FLASH_MS)
            flash.hide()
        }
    }
    return flash
}

@Composable
internal fun SaveRow(visible: Boolean, enabled: Boolean, saveLabel: String, savedFlash: Boolean, onClick: () -> Unit) {
    if (!visible && !savedFlash) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (visible) {
            Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp)) { Text(saveLabel) }
        }
        if (savedFlash) {
            Text("Saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun SaveableField(
    label: String,
    savedValue: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    placeholder: String? = null,
    saveLabel: String = "Save",
    allowBlank: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    // Keyed on savedValue: a value that arrives or changes later (IP flows, post-save) re-seeds the text.
    // Plain fields survive rotation via rememberSaveable; secrets stay out of the saved-state bundle.
    val textState = if (password) {
        remember(savedValue) { mutableStateOf(savedValue) }
    } else {
        rememberSaveable(savedValue) { mutableStateOf(savedValue) }
    }
    var text by textState
    val savedFlash = rememberSavedFlash()
    val button = saveButtonState(text, savedValue, allowBlank)
    Column(modifier.widthIn(max = MAX_FIELD_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType)
        )
        SaveRow(
            visible = button.visible,
            enabled = button.enabled,
            saveLabel = saveLabel,
            savedFlash = savedFlash.visible,
            onClick = {
                onSave(text.trim())
                savedFlash.show()
            }
        )
    }
}
