package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.components.icons.NavSettingsSelected
import java.io.File

/**
 * Invoked by [KKCTopAppBar] to open the Settings tab from the trailing gear icon it appends
 * after every screen's own `actions`. Provided once near the root of the composition (see
 * `NavGraph.kt`) so all ~20 existing `KKCTopAppBar` call sites get the icon without each one
 * needing to pass a Settings callback individually. Defaults to a no-op outside that provider.
 */
val LocalOnOpenSettings = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Whether a Sheet Tracker or Hours Tracker update is pending install. Read by [KKCTopAppBar] to
 * show a small dot on the Settings gear icon. Provided alongside [LocalOnOpenSettings] near the
 * root of the composition (see `NavGraph.kt`) for the same reason: so all ~20 `KKCTopAppBar` call
 * sites get it without each one needing its own parameter. Defaults to false outside that provider.
 */
val LocalHasPendingUpdates = staticCompositionLocalOf { false }

/**
 * Shared-transition scope wrapping the root `NavHost`. Together with [LocalKKCTopBarRouteScope]
 * it lets [KKCTopAppBar] stay pinned while routes slide beneath it: the background is a shared
 * element (identical on every screen, so it never visibly moves) and the title/actions crossfade
 * between screens. Null outside that `NavHost` — the bar then renders as a plain in-place header.
 */
val LocalKKCTopBarSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * The route's `AnimatedVisibilityScope`, provided per top-level tab via [ProvideKKCTopBarRoute].
 * Routes that don't provide it keep the old behavior (header slides with the screen).
 */
val LocalKKCTopBarRouteScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Opts a `composable(...)` route's [KKCTopAppBar] into the pinned/morphing header transition. */
@Composable
fun AnimatedVisibilityScope.ProvideKKCTopBarRoute(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKKCTopBarRouteScope provides this, content = content)
}

/** True inside [KKCTopAppBar]'s title/actions, so shared components only tag their top-bar instance. */
private val LocalInKKCTopBar = staticCompositionLocalOf { false }

/**
 * Tags a top-bar item (refresh, clock, mode switcher, ...) so that when the incoming screen's bar
 * has the same item it glides from its old spot to its new one. Items without a match on the other
 * screen stay in the bar's crossfading content layer. No-op outside [KKCTopAppBar] or outside an
 * opted-in route.
 */
@Composable
fun Modifier.kkcTopBarItem(key: String): Modifier =
    if (LocalInKKCTopBar.current) {
        then(kkcTopBarShared("kkc-top-bar-item:$key", zIndex = 3f, crossfade = false))
    } else {
        this
    }

/**
 * `sharedElement` (target only — matched items move, no double-draw dimming) or `sharedBounds`
 * (both screens drawn, crossfading). Empty outside an opted-in route.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun kkcTopBarShared(
    key: String,
    zIndex: Float,
    crossfade: Boolean,
    anchorStart: Boolean = false
): Modifier {
    val sharedScope = LocalKKCTopBarSharedScope.current ?: return Modifier
    val routeScope = LocalKKCTopBarRouteScope.current ?: return Modifier
    return with(sharedScope) {
        val state = rememberSharedContentState(key = key)
        when {
            !crossfade -> Modifier.sharedElement(state, routeScope, zIndexInOverlay = zIndex)
            // Titles differ in width ("Settings" vs the logo); don't stretch them to the animating
            // bounds — keep each at natural size, pinned to the leading edge.
            anchorStart -> Modifier.sharedBounds(
                state,
                routeScope,
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.None, Alignment.CenterStart),
                zIndexInOverlay = zIndex
            )
            else -> Modifier.sharedBounds(state, routeScope, zIndexInOverlay = zIndex)
        }
    }
}

/**
 * Very slight blue wash used as the background of every screen's [androidx.compose.material3.TopAppBar]
 * so headers read as one cohesive, lightly branded surface across the app.
 *
 * Theme-aware: tints the surface color a touch toward [primary][androidx.compose.material3.ColorScheme.primary]
 * at the leading edge and fades back to plain surface — subtle, not a band of color.
 *
 * Usage: set the bar's `containerColor = Color.Transparent` and apply
 * `modifier = Modifier.headerBackground()` so the theme header shows through.
 */
@Composable
fun headerGradientBrush(): Brush {
    val tokens = LocalKKCThemeTokens.current
    val surface = MaterialTheme.colorScheme.surface
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = tokens.surface.headerTintAlpha).compositeOver(surface)
    return Brush.horizontalGradient(
        listOf(tint, surface, tint)
    )
}

/**
 * Shared top-app-bar background. Themes may provide a synced SVG header image; when they do,
 * it is painted over the normal surface at low alpha. Missing/invalid artwork falls back to
 * the original subtle gradient.
 */
@Composable
fun Modifier.headerBackground(): Modifier {
    val tokens = LocalKKCThemeTokens.current
    val backgroundPath = tokens.header.backgroundPath
    if (backgroundPath.isNullOrBlank()) {
        return background(headerGradientBrush())
    }

    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(File(backgroundPath))
            .crossfade(false)
            .build(),
        imageLoader = imageLoader
    )

    return background(MaterialTheme.colorScheme.surface)
        .clipToBounds()
        .drawWithContent {
            val alpha = tokens.header.alpha.coerceIn(0f, 1f)
            with(painter) {
                draw(size = size, alpha = alpha)
            }
            drawContent()
        }
}

/**
 * Unified application TopAppBar that applies themed background artwork and standard shadow
 * elevation automatically. Prevents code duplication and ensures a consistent visual style
 * across all shop floor and administration screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun KKCTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = WindowInsets.statusBars,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        titleContentColor = MaterialTheme.colorScheme.onSurface
    )
) {
    val onOpenSettings = LocalOnOpenSettings.current
    val hasPendingUpdates = LocalHasPendingUpdates.current
    // Layers during a route transition (overlay z-order, bottom to top):
    //  0 background — target-only shared element; every screen paints the same header art, so
    //    the swap is invisible and the bar reads as static.
    //  1 content    — both screens' title/actions in the same bounds, crossfading; covers items
    //    only one screen has.
    //  2 title      — crossfades and slides if the title slot moves.
    //  3 items      — [kkcTopBarItem]-tagged items present on both screens glide to their new spot.
    // Background is a sibling, not a parent: a sharedElement parent hides the outgoing screen's
    // subtree, which would stop its title/actions from crossfading out.
    val backgroundShared = kkcTopBarShared("kkc-top-bar-background", zIndex = 0f, crossfade = false)
    val contentShared = kkcTopBarShared("kkc-top-bar-content", zIndex = 1f, crossfade = true)
    val titleShared = kkcTopBarShared("kkc-top-bar-title", zIndex = 2f, crossfade = true, anchorStart = true)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(backgroundShared)
                .headerBackground()
                .shadow(elevation = 2.dp, clip = false)
        )
        TopAppBar(
            title = {
                CompositionLocalProvider(LocalInKKCTopBar provides true) {
                    Box(modifier = titleShared) { title() }
                }
            },
            modifier = contentShared,
            navigationIcon = navigationIcon,
            actions = {
                CompositionLocalProvider(LocalInKKCTopBar provides true) {
                    actions()
                    BatteryIndicator(modifier = Modifier.kkcTopBarItem("battery"))
                    IconButton(onClick = onOpenSettings, modifier = Modifier.kkcTopBarItem("settings")) {
                        if (hasPendingUpdates) {
                            BadgedBox(badge = { Badge {} }) {
                                Icon(NavSettingsSelected, contentDescription = "Settings")
                            }
                        } else {
                            Icon(NavSettingsSelected, contentDescription = "Settings")
                        }
                    }
                }
            },
            windowInsets = windowInsets,
            colors = colors
        )
    }
}
