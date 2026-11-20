package io.github.uposum.romstore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.uposum.romstore.RomStoreApp
import io.github.uposum.romstore.core.art.ImageUrls
import io.github.uposum.romstore.core.model.Systems

/** width / height of each cover once it has loaded, shared by every frame. */
object ArtRatios {
    val known = mutableStateMapOf<String, Float>()
}

/** Romspedia resizes every image to one fixed size; drawn stretched into the
 * console's box shape, the art gets its real proportions back. */
fun isResizedArt(url: String?) = url != null && Regex("^https?://static\\.romspedia\\.com/").containsMatchIn(url)

fun clampArtRatio(ratio: Float) = ratio.coerceIn(0.5f, 2f)

/** The shape a cover frame takes: the art's own once known, else the console's box. */
fun coverRatio(url: String?, system: String): Float {
    if (url == null || isResizedArt(url)) return Systems.boxRatio(system)
    return ArtRatios.known[url]?.let(::clampArtRatio) ?: Systems.boxRatio(system)
}

/**
 * A game's cover in its real box shape. Artwork is never cropped: art whose
 * shape differs from the frame is fitted with a dimmed copy of itself behind
 * it. A cover that fails to load tries its alternatives, then shows the title.
 */
@Composable
fun Cover(
    url: String?,
    system: String,
    title: String,
    modifier: Modifier = Modifier,
    ratio: Float = coverRatio(url, system),
    showChip: Boolean = false,
    big: Boolean = false,
    /** "card" picks a small variant of known image hosts, "detail" the full one. */
    kind: String = if (big) "detail" else "card",
) {
    // Pictures that are not flat covers (3D cases, banners), even when saved earlier: the title tile instead.
    val url = url?.takeUnless { io.github.uposum.romstore.core.sources.SourceDefs.notACover(it) }
    val context = LocalContext.current
    val candidates = remember(url) {
        if (url == null) emptyList() else listOf(url) + runCatching { RomStoreApp.instance.store.artwork.alternatives(url) }.getOrDefault(emptyList())
    }
    var attempt by remember(url) { mutableStateOf(0) }
    var loaded by remember(url) { mutableStateOf(false) }
    val current = candidates.getOrNull(attempt)
    val stretch = isResizedArt(url)
    val artRatio = url?.let { ArtRatios.known[it] }
    val contain = artRatio != null && !stretch && kotlin.math.abs(artRatio - ratio) / ratio > 0.04f

    Box(modifier.aspectRatio(ratio).background(C.surface)) {
        if (current == null) {
            NoArt(title, system, big)
        } else {
            val request = remember(current, kind) { ImageRequest.Builder(context).data(ImageUrls.bestVariant(current, kind) ?: current).build() }
            if (contain && loaded) {
                AsyncImage(
                    model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.28f),
                )
            }
            if (!loaded) NoArt(title, system, big, faint = true)
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = when {
                    stretch -> ContentScale.FillBounds
                    contain -> ContentScale.Fit
                    else -> ContentScale.Crop
                },
                modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    loaded = true
                    val img = state.result.image
                    if (url != null && img.width > 0 && img.height > 0 && url !in ArtRatios.known) {
                        ArtRatios.known[url] = img.width.toFloat() / img.height
                    }
                },
                onError = { if (attempt < candidates.size) attempt++ },
            )
        }
        if (showChip) ConsoleChip(system, Modifier.align(Alignment.TopStart).padding(if (LocalDense.current) 3.dp else 6.dp))
    }
}

/** The tile for a game without art: its title on the console's colour. */
@Composable
fun NoArt(title: String, system: String, big: Boolean = false, faint: Boolean = false) {
    val tint = systemTint(system)
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(tint.copy(alpha = if (faint) 0.10f else 0.28f), C.surface)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (!faint) {
            // Text follows the tile's size: a 76 dp grid tile and a poster both read well.
            val titleSize = (maxWidth.value / 8f).coerceIn(9f, if (big) 24f else 15f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(maxWidth * 0.07f)) {
                Text(
                    title, color = C.text, textAlign = TextAlign.Center,
                    fontSize = titleSize.sp, fontWeight = FontWeight.Bold,
                    lineHeight = (titleSize * 1.2f).sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    Systems.label(system).uppercase(), color = tint, textAlign = TextAlign.Center,
                    fontSize = (titleSize * 0.62f).coerceAtLeast(7f).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (titleSize * 0.8f).sp,
                )
            }
        }
    }
}

@Composable
fun ConsoleChip(system: String, modifier: Modifier = Modifier) {
    Text(
        Systems.label(system).uppercase(),
        modifier = modifier.background(Color(0xCC0B0E13), RoundedCornerShape(if (LocalDense.current) 4.dp else 6.dp))
            .padding(horizontal = if (LocalDense.current) 4.dp else 6.dp, vertical = if (LocalDense.current) 1.dp else 3.dp),
        style = T.chip, fontSize = if (LocalDense.current) 7.sp else 9.sp, letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** A rounded pill with text (rating, year, size...). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = C.text, icon: ImageVector? = null, iconTint: Color = color) {
    Row(
        modifier.background(C.surface2, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

enum class ButtonKind { Primary, Secondary, Ghost, Danger }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    big: Boolean = false,
    onY: (() -> Unit)? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val bg = when (kind) {
        ButtonKind.Primary -> C.accent
        ButtonKind.Secondary -> C.surface2
        ButtonKind.Ghost -> Color.Transparent
        ButtonKind.Danger -> C.bad
    }
    val shape = RoundedCornerShape(if (big) 12.dp else 10.dp)
    Row(
        modifier
            .heightIn(min = if (big) 52.dp else 42.dp)
            .pressable(shape = shape, enabled = enabled, onY = onY, onClick = onClick)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f), shape)
            .then(if (kind == ButtonKind.Ghost) Modifier.border(1.dp, C.line, shape) else Modifier)
            .padding(horizontal = if (big) 22.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (content != null) {
            content()
        } else {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (big) 22.dp else 18.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, color = if (enabled) Color.White else C.text3, fontSize = if (big) 17.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** A square icon-only button. */
@Composable
fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = C.text, bg: Color = C.surface2) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(size).pressable(shape = shape, onClick = onClick).background(bg, shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.48f)) }
}

/** A dropdown that works with touch and the d-pad. */
@Composable
fun <T> Selector(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = if (LocalDense.current) 30.dp else 40.dp).pressable(shape = shape) { open = true }.background(C.surface, shape)
                .padding(horizontal = if (LocalDense.current) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second ?: label, color = C.text, fontSize = if (LocalDense.current) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = C.text2, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = C.surface2) {
            for ((value, text) in options) {
                DropdownMenuItem(
                    text = { Text(text, color = C.text, fontSize = 14.sp) },
                    trailingIcon = { if (value == selected) Icon(Icons.Rounded.Check, null, tint = C.accent) },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier, color: Color = C.accent) {
    if (fraction == null) {
        LinearProgressIndicator(modifier = modifier.height(4.dp).clip(CircleShape), color = color, trackColor = C.surface2, strokeCap = StrokeCap.Round)
    } else {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = modifier.height(4.dp).clip(CircleShape), color = color, trackColor = C.surface2, strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(C.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.text2, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = T.h2, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = T.body2, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun SectionTitle(text: String, count: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val dense = LocalDense.current
    Row(modifier.fillMaxWidth().padding(PaddingValues(bottom = if (dense) 6.dp else 12.dp)), verticalAlignment = Alignment.Bottom) {
        Text(text, style = T.h2, fontSize = if (dense) 15.sp else 19.sp)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, color = C.text3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A soft drop shadow for covers (cheap: no blur on scroll). */
fun Modifier.coverShadow(): Modifier = this.shadow(6.dp, RectangleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)

@Composable
fun Labeled(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = T.label)
        Spacer(Modifier.height(3.dp))
        Text(value, style = T.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

fun formatCount(value: Long?): String = when {
    value == null || value <= 0 -> ""
    value >= 1_000_000 -> String.format("%.1fM", value / 1e6)
    value >= 1000 -> String.format("%.1fk", value / 1e3)
    else -> value.toString()
}

fun formatEta(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0) return ""
    if (seconds < 60) return "${maxOf(1, seconds.toInt())} s"
    if (seconds < 3600) return "${(seconds / 60).toInt()} min"
    val h = (seconds / 3600).toInt()
    val m = ((seconds % 3600) / 60).toInt()
    return if (m > 0) "$h h $m min" else "$h h"
}

@Composable
fun BoxScope.Scrim() {
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg))))
}
