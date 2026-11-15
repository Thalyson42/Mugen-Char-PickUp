package com.ikemenselect.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikemenselect.core.Cell
import com.ikemenselect.core.CellKind
import com.ikemenselect.core.isBlankCell
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Grid de seleção de personagens, como no jogo.
 * - Toque: seleciona a célula.
 * - Toque longo + arrastar: move o personagem (soltar em célula vazia move; em ocupada, troca).
 */
@Composable
fun SelectGrid(
    cells: List<Cell?>,
    columns: Int,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onSwap: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
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

    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            state = gridState,
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            userScrollEnabled = dragFrom < 0,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            val i = cellAt(p)
                            if (i >= 0 && !latestCells.getOrNull(i).isBlankCell()) {
                                dragFrom = i
                                hover = i
                                dragPos = p
                                ghostSize = gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == i }?.size ?: IntSize.Zero
                            }
                        },
                        onDrag = { change, _ ->
                            if (dragFrom >= 0) {
                                change.consume()
                                dragPos = change.position
                                hover = cellAt(change.position)
                            }
                        },
                        onDragEnd = {
                            if (dragFrom >= 0 && hover >= 0 && hover != dragFrom) latestSwap(dragFrom, hover)
                            dragFrom = -1
                            hover = -1
                        },
                        onDragCancel = {
                            dragFrom = -1
                            hover = -1
                        },
                    )
                },
        ) {
            itemsIndexed(cells) { index, cell ->
                CellTile(
                    cell = cell,
                    isSelected = index == selected,
                    isTarget = index == hover && index != dragFrom,
                    isDragSource = index == dragFrom,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onSelect(index) },
                )
            }
        }

        if (dragFrom >= 0) {
            val density = LocalDensity.current
            val w = with(density) { ghostSize.width.toDp() }
            val h = with(density) { ghostSize.height.toDp() }
            CellTile(
                cell = cells.getOrNull(dragFrom),
                isSelected = true,
                isTarget = false,
                isDragSource = false,
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
    modifier: Modifier = Modifier,
) {
    val blank = cell.isBlankCell()
    val bg = when {
        blank -> Color(0xFF262626)
        cell!!.kind == CellKind.RANDOM -> Color(0xFF3A3A4A)
        cell.kind == CellKind.SKIP -> Color(0xFF2E2E2E)
        else -> Color.hsv((abs((cell.key ?: cell.label).hashCode()) % 360).toFloat(), 0.45f, 0.55f)
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
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!blank) {
            val c = cell!!
            when (c.kind) {
                CellKind.RANDOM -> Text("?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                else -> Text(
                    text = if (c.kind == CellKind.SLOT && c.members.size > 1) "${c.label}\n×${c.members.size}" else c.label,
                    color = Color.White,
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
