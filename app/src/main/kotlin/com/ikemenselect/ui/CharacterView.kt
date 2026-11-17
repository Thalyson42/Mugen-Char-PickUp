package com.ikemenselect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikemenselect.core.AnimNames
import com.ikemenselect.core.Blend
import com.ikemenselect.core.Placement
import com.ikemenselect.core.RectF
import com.ikemenselect.core.SpriteRect
import com.ikemenselect.viewer.CachedSprite
import com.ikemenselect.viewer.CharOption
import com.ikemenselect.viewer.LoadedChar
import com.ikemenselect.viewer.LoadedStage
import com.ikemenselect.viewer.Owner
import com.ikemenselect.viewer.StageOption
import com.ikemenselect.viewer.ViewerSource
import com.ikemenselect.viewer.ViewerUi
import com.ikemenselect.viewer.ViewerViewModel
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Animação em andamento de um personagem: qual, desde quando (em ticks do relógio) e se repete. */
private class Play(val anim: Int, val start: Float, val loop: Boolean)

private val GroupOrder = listOf("Movimento", "Normais", "Especiais", "Supers", "Outros", "Reações", "Outras")
private val CollapsedByDefault = setOf("Reações", "Outras")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterView(viewer: ViewerViewModel, source: ViewerSource?) {
    val ui by viewer.ui.collectAsStateWithLifecycle()
    LaunchedEffect(source) { source?.let { viewer.open(it) } }

    var paused by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showBoxes by remember { mutableStateOf(false) }
    var loopMove by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(Owner.P1) }
    // relógio em ticks (60 por segundo). Só é lido na hora de desenhar, para não recompor a tela a cada quadro.
    var clock by remember { mutableFloatStateOf(0f) }
    var play1 by remember { mutableStateOf(Play(0, 0f, true)) }
    var play2 by remember { mutableStateOf(Play(0, 0f, true)) }
    var picker by remember { mutableIntStateOf(0) } // 0 = fechado, 1 = P1, 2 = P2, 3 = cenário
    val scope = rememberCoroutineScope()

    LaunchedEffect(paused, speed) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            clock += (now - last) / 1_000_000_000f * 60f * speed
            last = now
        }
    }
    LaunchedEffect(ui.p1) { play1 = Play(0, clock, true) }
    LaunchedEffect(ui.p2) { play2 = Play(0, clock, true) }

    val stage = ui.stage
    val stW = stage?.def?.localCoordX ?: 320
    val stH = stage?.def?.localCoordY ?: 240

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(stW.toFloat() / stH.toFloat())
                    .clipToBounds()
                    .background(Color(0xFF1E2630)),
            ) {
                drawScene(viewer, ui, play1, play2, clock, showBoxes, stW, stH)
            }
            if (ui.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        ui.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // personagens e cenário
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { picker = 1 }, modifier = Modifier.weight(1f)) {
                Text("P1: " + (ui.p1?.name ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { picker = 2 }, modifier = Modifier.weight(1f)) {
                Text("P2: " + (ui.p2?.name ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { picker = 3 }, modifier = Modifier.weight(1f)) {
                Text(stage?.option?.label ?: "Cenário", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // reprodução
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = paused, onClick = { paused = !paused }, label = { Text(if (paused) "▶ Continuar" else "⏸ Pausar") })
            FilterChip(selected = speed == 1f, onClick = { speed = 1f }, label = { Text("1×") })
            FilterChip(selected = speed == 0.5f, onClick = { speed = 0.5f }, label = { Text("½×") })
            FilterChip(selected = speed == 0.25f, onClick = { speed = 0.25f }, label = { Text("¼×") })
            FilterChip(selected = loopMove, onClick = { loopMove = !loopMove }, label = { Text("Repetir golpe") })
            FilterChip(selected = showBoxes, onClick = { showBoxes = !showBoxes }, label = { Text("Caixas") })
        }

        // de quem são os botões
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = active == Owner.P1, onClick = { active = Owner.P1 }, label = { Text("Golpes do P1") })
            FilterChip(selected = active == Owner.P2, onClick = { active = Owner.P2 }, label = { Text("Golpes do P2") })
        }

        val c = if (active == Owner.P1) ui.p1 else ui.p2
        if (c == null) {
            Text(
                "Escolha um personagem em P1 ou P2 para ver as animações.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            AnimButtons(
                c = c,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onPlay = { id ->
                    val anim = c.anims[id] ?: return@AnimButtons
                    val owner = active
                    scope.launch {
                        viewer.ensureAnim(owner, c, anim)
                        val p = Play(id, clock, loopMove)
                        if (owner == Owner.P1) play1 = p else play2 = p
                    }
                },
            )
        }
    }

    when (picker) {
        1, 2 -> PickerDialog(
            title = if (picker == 1) "Personagem do P1" else "Personagem do P2",
            options = ui.chars.map { it.entry to it.label },
            allowNone = false,
            onPick = { id ->
                val opt: CharOption? = ui.chars.firstOrNull { it.entry == id }
                if (opt != null) viewer.pickChar(if (picker == 1) Owner.P1 else Owner.P2, opt)
                picker = 0
            },
            onDismiss = { picker = 0 },
        )
        3 -> PickerDialog(
            title = "Cenário",
            options = ui.stages.map { it.path to it.label },
            allowNone = true,
            onPick = { id ->
                val opt: StageOption? = ui.stages.firstOrNull { it.path == id }
                viewer.pickStage(opt)
                picker = 0
            },
            onDismiss = { picker = 0 },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimButtons(c: LoadedChar, modifier: Modifier, onPlay: (Int) -> Unit) {
    val groups = remember(c) {
        c.anims.keys.sorted().groupBy { AnimNames.group(it) }
    }
    val expanded = remember(c) { mutableStateMapOf<String, Boolean>() }
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        for (name in GroupOrder) {
            val ids = groups[name] ?: continue
            val open = expanded[name] ?: (name !in CollapsedByDefault)
            item(key = "h-$name") {
                Text(
                    (if (open) "▾ " else "▸ ") + "$name (${ids.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded[name] = !open }
                        .padding(vertical = 8.dp),
                )
            }
            if (open) {
                item(key = "b-$name") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (id in ids) {
                            val label = AnimNames.exact(id)?.let { "$id · $it" } ?: "$id"
                            OutlinedButton(onClick = { onPlay(id) }) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Os nomes seguem a convenção comum do Mugen; cada personagem pode usar os números de outro jeito.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    allowNone: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(options, filter) {
        options.filter { it.second.contains(filter, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Buscar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (options.isEmpty()) {
                    Text("Nada encontrado.", modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (allowNone) {
                        item { TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("Sem cenário") } }
                    }
                    items(shown, key = { it.first }) { (id, label) ->
                        TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- desenho

private fun DrawScope.drawScene(
    viewer: ViewerViewModel,
    ui: ViewerUi,
    play1: Play,
    play2: Play,
    clock: Float,
    showBoxes: Boolean,
    stW: Int,
    stH: Int,
) {
    val unit = size.width / stW
    val stage = ui.stage
    val ground = (stage?.def?.zoffset?.takeIf { it > 0 } ?: (stH * 5 / 6)).toFloat()

    if (stage != null) drawStageLayers(viewer, stage, clock, front = false, stW = stW, stH = stH, unit = unit)
    else drawLine(Color(0x55FFFFFF), Offset(0f, ground * unit), Offset(size.width, ground * unit), strokeWidth = 2f)

    val p1x = stW / 2f + (stage?.def?.p1StartX ?: -70)
    val p2x = stW / 2f + (stage?.def?.p2StartX ?: 70)
    drawChar(viewer, ui.p2, Owner.P2, play2, clock, p2x, ground, -1, stW, stH, unit, showBoxes)
    drawChar(viewer, ui.p1, Owner.P1, play1, clock, p1x, ground, 1, stW, stH, unit, showBoxes)

    if (stage != null) drawStageLayers(viewer, stage, clock, front = true, stW = stW, stH = stH, unit = unit)
}

private fun DrawScope.drawChar(
    viewer: ViewerViewModel,
    c: LoadedChar?,
    owner: Int,
    play: Play,
    clock: Float,
    px: Float,
    py: Float,
    facing: Int,
    stW: Int,
    stH: Int,
    unit: Float,
    showBoxes: Boolean,
) {
    if (c == null) return
    val idle = c.anims[0] ?: c.anims.values.firstOrNull() ?: return
    var anim = c.anims[play.anim] ?: idle
    var tick = (clock - play.start).toInt()
    var loop = play.loop
    if (!play.loop) {
        // golpe de uma vez só: depois que termina, volta para a animação parada
        val len = anim.totalTicks + if (anim.infinite) 30 else 0
        if (tick >= len) {
            anim = idle
            tick -= len
            loop = true
        }
    }
    val f = anim.frames.getOrNull(anim.frameIndexAt(tick, loop)) ?: return
    val sx = Placement.charScale(stW, c.localCoordX, c.size.xscale)
    val sy = Placement.charScale(stH, c.localCoordY, c.size.yscale)

    val cs = viewer.sprite(owner, f.group, f.image)
    if (cs != null) {
        val r = Placement.sprite(px, py, facing, f, cs.info, cs.w, cs.h, sx, sy)
        drawSprite(cs, r, unit, f.blend, f.alphaSrc)
    }
    if (showBoxes) {
        for (b in f.clsn2) drawBox(Placement.box(px, py, facing, b, sx, sy), Color(0xFF4DA3FF), unit)
        for (b in f.clsn1) drawBox(Placement.box(px, py, facing, b, sx, sy), Color(0xFFFF5A5A), unit)
    }
}

private fun DrawScope.drawStageLayers(
    viewer: ViewerViewModel,
    st: LoadedStage,
    clock: Float,
    front: Boolean,
    stW: Int,
    stH: Int,
    unit: Float,
) {
    for (l in st.def.layers) {
        if (l.type == "dummy" || (l.layerNo >= 1) != front) continue
        var group = l.spriteGroup
        var number = l.spriteNo
        var offX = 0
        var offY = 0
        if (l.type == "anim") {
            val a = st.def.animations[l.actionNo] ?: continue
            val f = a.frames.getOrNull(a.frameIndexAt(clock.toInt(), true)) ?: continue
            group = f.group
            number = f.image
            offX = f.x
            offY = f.y
        }
        val cs = viewer.sprite(Owner.STAGE, group, number) ?: continue
        val left = stW / 2f + l.startX + offX - cs.info.axisX
        val top = l.startY + offY - cs.info.axisY
        val xs = Placement.tiles(left, cs.w.toFloat(), l.tileSpacingX.toFloat(), l.tileX, stW.toFloat())
        val ys = Placement.tiles(top, cs.h.toFloat(), l.tileSpacingY.toFloat(), l.tileY, stH.toFloat())
        for (y in ys) for (x in xs) {
            drawSprite(cs, SpriteRect(x, y, cs.w.toFloat(), cs.h.toFloat(), false, false), unit, l.blend, l.alphaSrc)
        }
    }
}

private fun DrawScope.drawSprite(cs: CachedSprite, r: SpriteRect, unit: Float, blend: Blend, alphaSrc: Int) {
    if (blend == Blend.SUB) return // subtração ainda não é desenhada
    val dx = (r.left * unit).roundToInt()
    val dy = (r.top * unit).roundToInt()
    val dw = max(1, (r.width * unit).roundToInt())
    val dh = max(1, (r.height * unit).roundToInt())
    val mode = if (blend == Blend.ADD || blend == Blend.ADD1) BlendMode.Plus else BlendMode.SrcOver
    val alpha = when (blend) {
        Blend.ALPHA -> (alphaSrc / 256f).coerceIn(0f, 1f)
        Blend.ADD1 -> 0.5f
        else -> 1f
    }
    val pivot = Offset(dx + dw / 2f, dy + dh / 2f)
    withTransform({ scale(if (r.mirrorX) -1f else 1f, if (r.mirrorY) -1f else 1f, pivot) }) {
        drawImage(
            image = cs.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(cs.w, cs.h),
            dstOffset = IntOffset(dx, dy),
            dstSize = IntSize(dw, dh),
            alpha = alpha,
            blendMode = mode,
            filterQuality = FilterQuality.None,
        )
    }
}

private fun DrawScope.drawBox(r: RectF, color: Color, unit: Float) {
    drawRect(
        color = color,
        topLeft = Offset(r.left * unit, r.top * unit),
        size = Size((r.right - r.left) * unit, (r.bottom - r.top) * unit),
        style = Stroke(width = 2f),
    )
}
