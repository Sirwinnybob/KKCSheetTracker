package com.kkc.sheettracker.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.ui.components.icons.HardwoodsPlankIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsSpecialtyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceAssemblyIcon
import com.kkc.sheettracker.ui.components.icons.StationCncIcon

/** [tintable] = single-color vector the tile recolors; false for full-color artwork. */
internal class ModeLogo(val painter: Painter, val tintable: Boolean)

/**
 * The one place mode tile artwork comes from. Today: KKC station icons.
 * For the comic logos: add res/drawable-nodpi/mode_logo_{cnc,hardwoods,assembly,specialty}.png
 * (square, >= 512px, transparent) and return ModeLogo(painterResource(R.drawable.mode_logo_x), tintable = false).
 */
@Composable
internal fun workModeLogo(mode: WorkMode): ModeLogo {
    val vector = when (mode) {
        WorkMode.CNC -> StationCncIcon
        WorkMode.HARDWOODS -> HardwoodsPlankIcon
        WorkMode.ASSEMBLY -> ReferenceAssemblyIcon
        WorkMode.SPECIALTY -> HardwoodsSpecialtyIcon
    }
    return ModeLogo(rememberVectorPainter(vector), tintable = true)
}
