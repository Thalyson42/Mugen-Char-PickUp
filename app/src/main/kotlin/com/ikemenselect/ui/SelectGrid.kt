package com.ikemenselect.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikemenselect.Face
import com.ikemenselect.core.Cell
import com.ikemenselect.core.CellKind
import com.ikemenselect.core.isBlankCell
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Grid de seleção de personagens, como no jogo (com o retrato de cada um).
 * - Toque: seleciona a célula.
 * - Toque longo + arrastar: move o personagem (soltar em célula vazia move; em ocupada, troca).
 *
 * O toque longo é detectado na fase Initial do evento, ANTES da lista rolável (e do clique das
 * células) poderem consumir o gesto. Depois de ativado, todos os eventos do dedo são consumidos
 * para a lista não rolar nem a célula contar como clique.
 */
@Composable
fun SelectGrid(
    cells: List<Cell?>,
    columns: Int,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onSwap: (from: Int, to: Int) -> Unit,
    peekFace: (String) -> Face,
    loadFace: suspend (String) -> Face,
    faceEpoch: Int,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragFrom by remember { mutableIntStateOf(-1) }
    var hover by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var ghostSize by remember { mutableStateOf(IntSize.Zero) }
    val latestCells by rememberUpdatedState(cells)
    val latestSwap by rememberUpdatedState(onSwap)

    fun cellAt(p: Offset): Int {
        val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            p.x >= it.offset.x && p.x < it.offset.x + it.size.width &&
                p.y >= it.offset.y && p.y < it.offset.y + it.size.height
        }
        return hit?.index ?: -1
    }

    // Rolagem automática quando o dedo chega perto da borda de cima/baixo durante o arrasto.
    LaunchedEffect(dragFrom >= 0) {
        if (dragFrom < 0) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        while (true) {
            val h = gridState.layoutInfo.viewportSize.height.toFloat()
            val y = dragPos.y
            val dy = when {
                y < edge -> -(edge - y) / edge * 28f
                y > h - edge -> (y - (h - edge)) / edge * 28f
                else -> 0f
            }
            if (dy != 0f) {
                gridState.scrollBy(dy)
                hover = cellAt(dragPos)
            }
            delay(16)
        }
    }

    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            state = gridState,
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val start = cellAt(down.position)
                        if (start < 0 || latestCells.getOrNull(start).isBlankCell()) return@awaitEachGesture

                        // Espera o toque longo; cancela se o dedo soltar ou andar (então é toque ou rolagem).
                        val aborted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var abort = false
                            while (!abort) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = event.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed ||
                                    (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) abort = true
                            }
                            true
                        }
                        if (aborted != null) return@awaitEachGesture

                        dragFrom = start
                        hover = start
                        dragPos = down.position
                        ghostSize = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == start }?.size ?: IntSize.Zero
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                ch.consume()
                                if (!ch.pressed) {
                                    if (hover >= 0 && hover != start) latestSwap(start, hover)
                                    break
                                }
                                dragPos = ch.position
                                hover = cellAt(ch.position)
                            }
                        } finally {
                            dragFrom = -1
                            hover = -1
                        }
                    }
                },
        ) {
            itemsIndexed(cells) { index, cell ->
                CellTile(
                    cell = cell,
                    isSelected = index == selected,
                    isTarget = index == hover && index != dragFrom,
                    isDragSource = index == dragFrom,
                    peekFace = peekFace,
                    loadFace = loadFace,
                    faceEpoch = faceEpoch,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onSelect(index) },
                )
            }
        }

        if (dragFrom >= 0) {
            val w = with(density) { ghostSize.width.toDp() }
            val h = with(density) { ghostSize.height.toDp() }
            CellTile(
                cell = cells.getOrNull(dragFrom),
                isSelected = true,
                isTarget = false,
                isDragSource = false,
                peekFace = peekFace,
                loadFace = loadFace,
                faceEpoch = faceEpoch,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragPos.x - ghostSize.width / 2f).roundToInt(),
                            (dragPos.y - ghostSize.height / 2f).roundToInt(),
                        )
                    }
                    .size(w, h)
                    .alpha(0.85f),
            )
        }
    }
}

@Composable
private fun CellTile(
    cell: Cell?,
    isSelected: Boolean,
    isTarget: Boolean,
    isDragSource: Boolean,
    peekFace: (String) -> Face,
    loadFace: suspend (String) -> Face,
    faceEpoch: Int,
    modifier: Modifier = Modifier,
) {
    val blank = cell.isBlankCell()
    val entry = cell?.takeIf { it.kind == CellKind.CHAR || it.kind == CellKind.SLOT }?.entry

    // O retrato é carregado só quando a célula aparece na tela (a lista é preguiçosa).
    val face by produceState<Face>(
        initialValue = entry?.let(peekFace) ?: Face.Missing,
        entry, faceEpoch,
    ) {
        if (entry != null && value !is Face.Image) value = loadFace(entry)
    }

    val bg = when {
        blank -> Color(0xFF262626)
        cell!!.kind == CellKind.RANDOM -> Color(0xFF3A3A4A)
        cell.kind == CellKind.SKIP -> Color(0xFF2E2E2E)
        else -> Color(0xFF1E1E24)
    }
    val border = when {
        isTarget -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, Color(0x33FFFFFF))
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .alpha(if (isDragSource) 0.35f else 1f)
            .background(bg, shape)
            .border(border, shape)
            .padding(2.dp)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!blank) {
            val c = cell!!
            val f = face
            when {
                c.kind == CellKind.RANDOM ->
                    Text("?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

                f is Face.Image -> Image(
                    bitmap = f.bitmap,
                    contentDescription = c.label,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    filterQuality = FilterQuality.None, // pixels nítidos, como no jogo
                    modifier = Modifier.fillMaxSize(),
                )

                // Sem retrato (ainda carregando ou o personagem não tem 9000,0): mostra o nome.
                else -> Text(
                    text = if (c.kind == CellKind.SLOT && c.members.size > 1) "${c.label}\n×${c.members.size}" else c.label,
                    color = Color.White.copy(alpha = if (f is Face.Loading) 0.45f else 1f),
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
