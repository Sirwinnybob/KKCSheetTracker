package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.IdlePowerSaveConfig
import com.kkc.sheettracker.data.IdlePowerSaveStore
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.GroupDivider
import com.kkc.sheettracker.ui.settings.SettingToggle
import com.kkc.sheettracker.ui.settings.settingsFieldColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PerformancePowerPane(
    uiPreferencesStore: UiPreferencesStore,
    idlePowerSaveStore: IdlePowerSaveStore,
) {
    var lowEndMode by remember { mutableStateOf(uiPreferencesStore.getLowEndMode()) }
    var animationsEnabled by remember { mutableStateOf(uiPreferencesStore.getAnimationsEnabled()) }
    var shadowsEnabled by remember { mutableStateOf(uiPreferencesStore.getShadowsEnabled()) }
    var blurEnabled by remember { mutableStateOf(uiPreferencesStore.getBlurEnabled()) }
    var lazyLoadingEnabled by remember { mutableStateOf(uiPreferencesStore.getLazyLoadingEnabled()) }

    val idleConfig by idlePowerSaveStore.configFlow.collectAsState(initial = IdlePowerSaveConfig())
    val scope = rememberCoroutineScope()
    var idleTimeoutText by remember(idleConfig.idleTimeoutSeconds) {
        mutableStateOf(idleConfig.idleTimeoutSeconds.toString())
    }
    LaunchedEffect(idleTimeoutText) {
        val seconds = idleTimeoutText.toIntOrNull() ?: return@LaunchedEffect
        delay(500L)
        idlePowerSaveStore.setIdleTimeoutSeconds(seconds)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Performance") {
            SettingToggle(
                label = "Low-end device mode",
                checked = lowEndMode,
                onCheckedChange = { enabled ->
                    lowEndMode = enabled
                    uiPreferencesStore.setLowEndMode(enabled)
                    if (enabled) {
                        animationsEnabled = false
                        shadowsEnabled = false
                        blurEnabled = false
                        lazyLoadingEnabled = true
                        uiPreferencesStore.setAnimationsEnabled(false)
                        uiPreferencesStore.setShadowsEnabled(false)
                        uiPreferencesStore.setBlurEnabled(false)
                        uiPreferencesStore.setLazyLoadingEnabled(true)
                    }
                }
            )
            if (lowEndMode) {
                Column(Modifier.padding(start = 16.dp)) {
                    GroupDivider()
                    SettingToggle("Animations", animationsEnabled, {
                        animationsEnabled = it
                        uiPreferencesStore.setAnimationsEnabled(it)
                    }, "Spring/tween transitions, animated content size")
                    SettingToggle("Shadows", shadowsEnabled, {
                        shadowsEnabled = it
                        uiPreferencesStore.setShadowsEnabled(it)
                    }, "Card/button elevation shadows")
                    SettingToggle("Frosted glass / blur", blurEnabled, {
                        blurEnabled = it
                        uiPreferencesStore.setBlurEnabled(it)
                    }, "hazeEffect() backgrounds, blur modifiers")
                    SettingToggle("Lazy data loading", lazyLoadingEnabled, {
                        lazyLoadingEnabled = it
                        uiPreferencesStore.setLazyLoadingEnabled(it)
                    }, "Paginate job/supply lists, defer heavy loads")
                }
            }
        }

        GroupCard(caption = "Idle power saving") {
            SettingToggle(
                label = "Enable idle power saving",
                checked = idleConfig.enabled,
                onCheckedChange = { enabled -> scope.launch { idlePowerSaveStore.setEnabled(enabled) } },
                subtitle = "Switches to dark sheets + black background to save battery on tablets left on but idle. Reverts instantly on touch."
            )
            if (idleConfig.enabled) {
                GroupDivider()
                CardBody {
                    OutlinedTextField(
                        value = idleTimeoutText,
                        onValueChange = { idleTimeoutText = it },
                        label = { Text("Dim after (seconds)") },
                        supportingText = { Text("Lower values (e.g. 5) are useful for testing. Default 300 (5 min).") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    }
}
