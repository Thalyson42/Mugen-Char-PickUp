package com.ikemenselect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import com.ikemenselect.R
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.ColorFilter
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.ikemenselect.core.AnimNames
import com.ikemenselect.core.AnimStep
import com.ikemenselect.core.Animation
import com.ikemenselect.core.Bind
import com.ikemenselect.core.SoundEvent
import com.ikemenselect.core.CatalogEntry
import com.ikemenselect.core.Blend
import com.ikemenselect.core.Placement
import com.ikemenselect.core.RectF
import com.ikemenselect.core.SpriteRect
import com.ikemenselect.core.TargetPlan
import com.ikemenselect.core.sim.SimFrame
import com.ikemenselect.core.sim.SimTake
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

/**
 * O que um personagem está fazendo: uma sequência de trechos de animação (um golpe inteiro ou uma animação só).
 * Depois de acabar, volta para a animação parada. [delay]: ticks de espera até começar (usado pelo alvo de um golpe).
 */
private class Play(
    val steps: List<AnimStep>,
    val start: Float,
    val delay: Int = 0,
    val repeat: Boolean = false,
    /** Duração de uma volta, quando [repeat]. */
    val period: Int = 0,
    /** Dono dos trechos com useOwner (animações do atacante, desenhadas no alvo). */
    val ownerChar: LoadedChar? = null,
    val ownerId: Int = 0,
    /** Para o alvo de um golpe: quem ataca (a posição dos [binds] é relativa a ele). */
    val attacker: Int = 0,
    val binds: List<Bind> = emptyList(),
    val bindEnd: Float = 0f,
    /** Etapas do alvo em golpes encadeados: a cada uma o atacante manda o alvo para outro estado (substitui [steps]). */
    val phases: List<TargetPlan> = emptyList(),
    /** `sprpriority` do estado de quem ataca (do alvo vale o da etapa ativa em [phases]). */
    val priority: Int = 0,
)

private fun activePhase(play: Play, clock: Float): TargetPlan? =
    if (play.phases.isEmpty()) null else playTime(play, clock).let { t -> play.phases.lastOrNull { it.startTick <= t } }

private fun priorityOf(play: Play, clock: Float): Int =
    if (play.phases.isNotEmpty()) activePhase(play, clock)?.sprPriority ?: 0 else play.priority

private val IdlePlay = Play(emptyList(), 0f)

/**
 * Uma simulação gravada sendo reproduzida. O lado 1 da gravação é quem ataca ([atk]); o lado 2, o adversário ([tgt]).
 * Com [mirror] (o atacante é o P2) a cena é espelhada para o P2 continuar na direita.
 */
private class TakePlay(
    val take: SimTake,
    val start: Float,
    val repeat: Boolean,
    val atk: LoadedChar,
    val atkOwner: Int,
    val tgt: LoadedChar?,
    val tgtOwner: Int,
    val mirror: Boolean,
)

/** Sons de um golpe de [ownerId]: tocam quando o relógio passa do tick de cada um (e de novo a cada volta, se repetir). */
private class SoundPlay(
    val events: List<SoundEvent>,
    val ownerId: Int,
    val start: Float,
    val period: Int,
    val repeat: Boolean,
) {
    var last = -1f // instante (relativo ao início) até onde já foi verificado
}

/** Toca os sons cujo tick passou desde a última verificação. */
private fun fireSounds(viewer: ViewerViewModel, plays: List<SoundPlay>, clock: Float) {
    for (sp in plays) {
        val now = clock - sp.start
        if (now < sp.last) continue
        if (now - sp.last > 30f) { sp.last = now; continue } // salto grande (voltou do segundo plano): não toca tudo de uma vez
        for (e in sp.events) {
            val t = e.tick.toFloat()
            if (!sp.repeat || sp.period <= 0) {
                if (sp.last < t && t <= now) viewer.playSound(sp.ownerId, e.ref)
            } else {
                val first = Math.floor(((sp.last - t) / sp.period).toDouble()).toInt() + 1
                val lastK = Math.floor(((now - t) / sp.period).toDouble()).toInt()
                if (lastK >= maxOf(first, 0)) viewer.playSound(sp.ownerId, e.ref) // no máximo um por verificação
            }
        }
        sp.last = now
    }
}

/** Animação a desenhar agora e de quem são os sprites e a escala. */
private class Shown(val anim: Animation, val tick: Int, val loop: Boolean, val spriteOwner: Int, val c: LoadedChar)

private fun naturalLen(a: Animation): Int = a.totalTicks + if (a.infinite) 30 else 0

private fun playTime(play: Play, clock: Float): Int {
    var t = (clock - play.start).toInt()
    if (play.repeat && play.period > 0 && t >= 0) t %= play.period
    return t
}

private fun resolve(c: LoadedChar, id: Int, play: Play, clock: Float): Shown? {
    val idle = c.anims[0] ?: c.anims.values.firstOrNull() ?: return null
    val raw = playTime(play, clock)
    var steps = play.steps
    var t = raw - play.delay
    var lastPhase = true
    if (play.phases.isNotEmpty()) {
        // a etapa de agora é a última que já começou; cada uma conta o tempo a partir do próprio início
        val i = play.phases.indexOfLast { it.startTick <= raw }
        if (i < 0) {
            t = -1
        } else {
            steps = play.phases[i].steps
            t = raw - play.phases[i].startTick
            lastPhase = i == play.phases.lastIndex
        }
    }
    if (t < 0 || steps.isEmpty()) return Shown(idle, clock.toInt(), true, id, c)
    var acc = t
    var lastShown: Shown? = null
    for (s in steps) {
        val useOwner = s.useOwner && play.ownerChar != null
        val ch = if (useOwner) play.ownerChar!! else c
        val a = ch.anims[s.anim] ?: continue
        val dur = if (s.ticks >= 0) s.ticks else naturalLen(a)
        if (acc < dur) return Shown(a, acc, false, if (useOwner) play.ownerId else id, ch)
        acc -= dur
        lastShown = Shown(a, naturalLen(a) + 1000, false, if (useOwner) play.ownerId else id, ch) // último quadro
    }
    // terminou: numa etapa do meio do golpe fica no último quadro (a próxima etapa ainda vai chegar); no fim volta ao parado
    if (!lastPhase && lastShown != null) return lastShown
    return Shown(idle, acc, true, id, c)
}

/** Posição do alvo preso ao atacante (TargetBind), ou null se não está preso agora. */
private fun boundPos(
    play: Play, clock: Float, atk: LoadedChar?, atkX: Float, atkFacing: Int, ground: Float, stW: Int, stH: Int,
): Pair<Float, Float>? {
    if (play.binds.isEmpty() || atk == null || (!play.repeat && clock >= play.bindEnd)) return null
    val t = playTime(play, clock)
    var cur: Bind? = null
    for (b in play.binds) if (b.start <= t) cur = b else break
    if (cur == null) return null
    val sx = Placement.charScale(stW, atk.localCoordX, atk.size.xscale)
    val sy = Placement.charScale(stH, atk.localCoordY, atk.size.yscale)
    return (atkX + atkFacing * cur.dx * sx) to (ground + cur.dy * sy)
}

/** Imagem pequena no lugar do texto de um botão. Com [tint] pinta com a cor do texto do tema (ícones de um tom só). */
@Composable
private fun ChipImage(res: Int, description: String, tint: Boolean = true) {
    Image(
        painter = painterResource(res),
        contentDescription = description,
        colorFilter = if (tint) ColorFilter.tint(LocalContentColor.current) else null,
        modifier = Modifier.height(22.dp),
    )
}

private class BtnItem(val id: Int, val label: String, val group: String, val entry: CatalogEntry? = null)

private fun buildMoves(catalog: List<CatalogEntry>): List<BtnItem> =
    catalog.map { e ->
        val cmds = e.commands.take(2).joinToString("/")
        val label = listOfNotNull(cmds.ifEmpty { null }, "${e.stateNo}", AnimNames.exact(e.stateNo), e.variant).joinToString(" · ")
        // cada estado é um botão (como antes); e.internal marca as etapas de outro golpe, para uma lista futura
        // estados de controle (anim 9999 e nenhum alvo com imagem) não mostram nada: ficam num grupo recolhido
        val invisible = e.invisible
        val group = when {
            invisible -> "Internos"
            e.helper -> "Helpers"
            else -> AnimNames.group(e.stateNo)
        }
        BtnItem(e.stateNo, label, group, e)
    }

private fun buildAnims(c: LoadedChar): List<BtnItem> =
    c.anims.keys.sorted().map { id ->
        BtnItem(id, AnimNames.exact(id)?.let { "$id · $it" } ?: "$id", AnimNames.group(id))
    }

private val GroupOrder = listOf("Movimento", "Normais", "Especiais", "Supers", "Outros", "Helpers", "Reações", "Outras", "Internos")
private val CollapsedByDefault = setOf("Helpers", "Reações", "Outras", "Internos")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterView(viewer: ViewerViewModel, source: ViewerSource?, dialog: Int, onDialog: (Int) -> Unit) {
    val ui by viewer.ui.collectAsStateWithLifecycle()
    LaunchedEffect(source) { source?.let { viewer.open(it) } }

    var paused by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showBoxes by remember { mutableStateOf(false) }
    var loopMove by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(Owner.P1) }
    // relógio em ticks (60 por segundo). Só é lido na hora de desenhar, para não recompor a tela a cada quadro.
    var clock by remember { mutableFloatStateOf(0f) }
    var play1 by remember { mutableStateOf(IdlePlay) }
    var play2 by remember { mutableStateOf(IdlePlay) }
    var takePlay by remember { mutableStateOf<TakePlay?>(null) }
    var listMode by remember { mutableIntStateOf(0) } // 0 = golpes (estados), 1 = animações
    var note by remember { mutableStateOf<String?>(null) }
    var soundOn by remember { mutableStateOf(true) }
    val soundPlays = remember { ArrayList<SoundPlay>() }
    val scope = rememberCoroutineScope()

    // saiu da tela (apps recentes, outro app, bloqueio): pausa e cala tudo, para o som não ficar preso
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        paused = true
        soundPlays.clear()
        viewer.stopSounds()
    }
    LaunchedEffect(paused, speed) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            clock += ((now - last) / 1_000_000_000f).coerceAtMost(0.1f) * 60f * speed
            last = now
            if (soundOn && soundPlays.isNotEmpty()) fireSounds(viewer, soundPlays, clock)
        }
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(4000)
            note = null
        }
    }
    LaunchedEffect(ui.p1) { play1 = IdlePlay; takePlay = null }
    LaunchedEffect(ui.p2) { play2 = IdlePlay; takePlay = null }

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
                drawScene(viewer, ui, play1, play2, takePlay, clock, showBoxes, stW, stH)
            }
            if (ui.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            // aviso sobre o cenário (não ocupa espaço na coluna, para os botões não se mexerem)
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0x99000000))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        ui.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // reprodução
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = paused, onClick = { paused = !paused }, label = { Text(if (paused) "▶" else "⏸") })
            Box {
                var speedMenu by remember { mutableStateOf(false) }
                FilterChip(
                    selected = false,
                    onClick = { speedMenu = true },
                    label = { Text(speedLabel(speed) + " ▾") },
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    for (v in Speeds) {
                        DropdownMenuItem(
                            text = { Text(speedLabel(v) + if (v == speed) "  ✓" else "") },
                            onClick = { speed = v; speedMenu = false },
                        )
                    }
                }
            }
            FilterChip(selected = loopMove, onClick = { loopMove = !loopMove }, label = { ChipImage(R.drawable.ic_repeat, "Repetir golpe") })
            FilterChip(
                selected = soundOn,
                onClick = {
                    soundOn = !soundOn
                    if (!soundOn) viewer.stopSounds()
                },
                label = { Text(if (soundOn) "🔊" else "🔇") },
            )
            FilterChip(selected = showBoxes, onClick = { showBoxes = !showBoxes }, label = { ChipImage(R.drawable.ic_boxes, "Caixas", tint = false) })
        }

        // de quem são os botões
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = active == Owner.P1, onClick = { active = Owner.P1 }, label = { Text("P1") })
            FilterChip(selected = active == Owner.P2, onClick = { active = Owner.P2 }, label = { Text("P2") })
        }

        // quem ataca e quem sofre (o outro personagem)
        val atkId = active
        val tgtId = if (atkId == Owner.P1) Owner.P2 else Owner.P1
        val c = if (atkId == Owner.P1) ui.p1 else ui.p2
        val other = if (tgtId == Owner.P1) ui.p1 else ui.p2

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = listMode == 0, onClick = { listMode = 0 }, label = { ChipImage(R.drawable.ic_moves, "Golpes") })
            FilterChip(selected = listMode == 1, onClick = { listMode = 1 }, label = { Text("Animações") })
        }

        if (c == null) {
            Text(
                "Escolha um personagem em P1 ou P2 para ver os golpes.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else if (listMode == 0 && c.catalog == null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Analisando os golpes do personagem…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val items = remember(c, c.catalog, listMode) {
                if (listMode == 0) c.catalog?.let { buildMoves(it) }.orEmpty() else buildAnims(c)
            }
            if (items.isEmpty()) {
                Text("Nada para mostrar aqui.", modifier = Modifier.padding(16.dp))
            } else {
                GroupedButtons(
                    items = items,
                    resetKey = Pair(c, listMode),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    footer = if (listMode == 0) {
                        "Cada botão é um estado do personagem. O app roda uma simulação do motor com os arquivos do personagem " +
                            "(estados, HitDef, helpers) e mostra o resultado."
                    } else {
                        "Os nomes seguem a convenção comum do Mugen; cada personagem pode usar os números de outro jeito."
                    },
                    onClick = { btn ->
                        val id = btn.id
                        note = null
                        soundPlays.clear()
                        viewer.stopSounds()
                        scope.launch {
                            val now = clock
                            if (listMode == 1) {
                                val a = c.anims[id] ?: return@launch
                                viewer.ensureAnims(atkId, c, listOf(a))
                                val p = Play(listOf(AnimStep(id, -1, false)), now, repeat = loopMove, period = naturalLen(a))
                                takePlay = null
                                if (atkId == Owner.P1) play1 = p else play2 = p
                                return@launch
                            }
                            // o simulador roda o estado e grava; a tela só reproduz o que ele fez
                            val take = viewer.record(c, other, btn.entry, id)
                            if (take == null) {
                                note = "Os estados do personagem ainda estão sendo lidos."
                                return@launch
                            }
                            val atkAnims = take.animsBySide[1].orEmpty().mapNotNull { c.anims[it] }
                            viewer.ensureAnims(atkId, c, atkAnims)
                            if (other != null) {
                                val tgtAnims = take.animsBySide[2].orEmpty().mapNotNull { other.anims[it] }
                                if (tgtAnims.isNotEmpty()) viewer.ensureAnims(tgtId, other, tgtAnims)
                            }

                            // sons: cada lado usa o .snd do seu dono
                            val s1 = take.sounds.filter { it.side == 1 }.map { SoundEvent(it.tick, it.ref) }
                            val s2 = if (other != null) take.sounds.filter { it.side == 2 }.map { SoundEvent(it.tick, it.ref) } else emptyList()
                            if (soundOn) {
                                viewer.prepareSounds(atkId, c, s1.map { it.ref })
                                if (other != null && s2.isNotEmpty()) viewer.prepareSounds(tgtId, other, s2.map { it.ref })
                            }

                            val start = clock
                            val period = take.ticks.coerceAtLeast(1)
                            soundPlays.clear()
                            if (s1.isNotEmpty()) soundPlays.add(SoundPlay(s1, atkId, start, period, loopMove))
                            if (s2.isNotEmpty()) soundPlays.add(SoundPlay(s2, tgtId, start, period, loopMove))
                            play1 = IdlePlay
                            play2 = IdlePlay
                            takePlay = TakePlay(take, start, loopMove, c, atkId, other, tgtId, mirror = atkId == Owner.P2)

                            // o que a pessoa precisa saber quando a tela parece não fazer nada
                            val visible = take.frames.any { f ->
                                f.side == 1 && c.anims[f.anim]?.frames?.any { fr -> viewer.sprite(atkId, fr.group, fr.image) != null } == true
                            }
                            val parts = ArrayList<String>()
                            if (take.scenario.isNotEmpty()) parts += "Cenário simulado: ${take.scenario}."
                            if (!visible) parts += "Nenhuma animação desse estado tem sprite visível, por isso o personagem some."
                            if (other != null) {
                                if (take.hit) parts += "O adversário passa pelos estados ${take.enemyStates.joinToString(", ")}."
                                else if (take.tried) parts += "O golpe não acertou o adversário nesta simulação (ele fica parado, a uma distância curta)."
                            }
                            if (take.stuck) parts += "Esse estado ficou parado esperando algo que a simulação não fornece (um comando ou um helper, por exemplo); mostrei os primeiros ${take.ticks / 60} s."
                            else if (!take.settled) parts += "A simulação chegou ao limite de segurança (${take.ticks / 60} s) sem terminar."
                            note = parts.joinToString(" ").ifEmpty { null }
                        }
                    },
                )
            }
        }
    }

    when (dialog) {
        1 -> CharSelectDialog(
            chars = ui.chars,
            p1 = ui.p1?.option?.entry,
            p2 = ui.p2?.option?.entry,
            onOk = { e1, e2 ->
                if (e1 != null && e1 != ui.p1?.option?.entry) ui.chars.firstOrNull { it.entry == e1 }?.let { viewer.pickChar(Owner.P1, it) }
                if (e2 != null && e2 != ui.p2?.option?.entry) ui.chars.firstOrNull { it.entry == e2 }?.let { viewer.pickChar(Owner.P2, it) }
                onDialog(0)
            },
            onDismiss = { onDialog(0) },
        )
        2 -> StageSelectDialog(
            stages = ui.stages,
            current = ui.stage?.option?.path,
            onOk = { path ->
                if (path != ui.stage?.option?.path) viewer.pickStage(ui.stages.firstOrNull { it.path == path })
                onDialog(0)
            },
            onDismiss = { onDialog(0) },
        )
    }
}

private val Speeds = listOf(1f, 0.5f, 0.25f)

private fun speedLabel(v: Float) = when (v) {
    0.5f -> "½×"
    0.25f -> "¼×"
    else -> "1×"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupedButtons(
    items: List<BtnItem>,
    resetKey: Any,
    modifier: Modifier,
    footer: String,
    onClick: (BtnItem) -> Unit,
) {
    val groups = remember(items) { items.groupBy { it.group } }
    val expanded = remember(resetKey) { mutableStateMapOf<String, Boolean>() }
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        for (name in GroupOrder) {
            val list = groups[name] ?: continue
            val open = expanded[name] ?: (name !in CollapsedByDefault)
            item(key = "h-$name") {
                Text(
                    (if (open) "▾ " else "▸ ") + "$name (${list.size})",
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
                        for (m in list) {
                            OutlinedButton(onClick = { onClick(m) }) { Text(m.label, maxLines = 1) }
                        }
                    }
                }
            }
        }
        item {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Lista com busca; o item marcado é [selected]. [noneLabel] != null acrescenta a opção "nenhum". */
@Composable
private fun SelectList(
    options: List<Pair<String, String>>,
    selected: String?,
    noneLabel: String?,
    onSelect: (String?) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(options, filter) { options.filter { it.second.contains(filter, ignoreCase = true) } }
    Column {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Buscar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (options.isEmpty()) Text("Nada encontrado.", modifier = Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            if (noneLabel != null) {
                item {
                    TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (selected == null) "● " else "○ ") + noneLabel, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            items(shown, key = { it.first }) { (id, label) ->
                TextButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (if (id == selected) "● " else "○ ") + label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Escolha do P1 e do P2 em uma janela só; as trocas valem ao tocar em OK. */
@Composable
private fun CharSelectDialog(
    chars: List<CharOption>,
    p1: String?,
    p2: String?,
    onOk: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var side by remember { mutableIntStateOf(1) }
    var pick1 by remember { mutableStateOf(p1) }
    var pick2 by remember { mutableStateOf(p2) }
    val options = remember(chars) { chars.map { it.entry to it.label } }
    fun nameOf(e: String?) = chars.firstOrNull { it.entry == e }?.label ?: "—"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Character select") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = side == 1, onClick = { side = 1 },
                        label = { Text("P1: " + nameOf(pick1), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = side == 2, onClick = { side = 2 },
                        label = { Text("P2: " + nameOf(pick2), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f),
                    )
                }
                SelectList(
                    options = options,
                    selected = if (side == 1) pick1 else pick2,
                    noneLabel = null,
                    onSelect = { if (side == 1) pick1 = it else pick2 = it },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(pick1, pick2) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun StageSelectDialog(
    stages: List<StageOption>,
    current: String?,
    onOk: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pick by remember { mutableStateOf(current) }
    val options = remember(stages) { stages.map { it.path to it.label } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stage select") },
        text = { SelectList(options = options, selected = pick, noneLabel = "Sem stage", onSelect = { pick = it }) },
        confirmButton = { TextButton(onClick = { onOk(pick) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- desenho

private fun DrawScope.drawScene(
    viewer: ViewerViewModel,
    ui: ViewerUi,
    play1: Play,
    play2: Play,
    take: TakePlay?,
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

    if (take != null) {
        drawTake(viewer, take, clock, showBoxes, stW, stH, unit, ground)
        if (stage != null) drawStageLayers(viewer, stage, clock, front = true, stW = stW, stH = stH, unit = unit)
        return
    }

    val home1 = stW / 2f + (stage?.def?.p1StartX ?: -70)
    val home2 = stW / 2f + (stage?.def?.p2StartX ?: 70)
    // quem está preso a um atacante (arremesso) é desenhado na posição do TargetBind
    val pos1 = boundPos(play1, clock, if (play1.attacker == Owner.P2) ui.p2 else null, home2, -1, ground, stW, stH)
        ?: (home1 to ground)
    val pos2 = boundPos(play2, clock, if (play2.attacker == Owner.P1) ui.p1 else null, home1, 1, ground, stW, stH)
        ?: (home2 to ground)

    // Offset do estado do alvo: desloca só o desenho
    fun shifted(pos: Pair<Float, Float>, play: Play, c: LoadedChar?, facing: Int): Pair<Float, Float> {
        val ph = activePhase(play, clock) ?: return pos
        if (c == null || (ph.offsetX == 0f && ph.offsetY == 0f)) return pos
        val sx = Placement.charScale(stW, c.localCoordX, c.size.xscale)
        val sy = Placement.charScale(stH, c.localCoordY, c.size.yscale)
        return (pos.first + facing * ph.offsetX * sx) to (pos.second + ph.offsetY * sy)
    }
    // p2facing do HitDef: 1 = o alvo fica virado para o mesmo lado do atacante, -1 = virado para ele
    fun targetFacing(play: Play, own: Int): Int {
        val f = activePhase(play, clock)?.facing ?: 0
        if (f == 0 || play.attacker == 0) return own
        val atk = -own // o atacante olha para o lado oposto ao do alvo na posição inicial
        return if (f > 0) atk else own
    }
    val face1 = targetFacing(play1, 1)
    val face2 = targetFacing(play2, -1)
    val d1 = shifted(pos1, play1, ui.p1, face1)
    val d2 = shifted(pos2, play2, ui.p2, face2)

    // sprpriority: quem tem o maior valor fica por cima; empate mantém o P1 na frente
    val onTop2 = priorityOf(play2, clock) > priorityOf(play1, clock)
    if (onTop2) drawChar(viewer, ui.p1, Owner.P1, play1, clock, d1.first, d1.second, face1, stW, stH, unit, showBoxes)
    drawChar(viewer, ui.p2, Owner.P2, play2, clock, d2.first, d2.second, face2, stW, stH, unit, showBoxes)
    if (!onTop2) drawChar(viewer, ui.p1, Owner.P1, play1, clock, d1.first, d1.second, face1, stW, stH, unit, showBoxes)

    if (stage != null) drawStageLayers(viewer, stage, clock, front = true, stW = stW, stH = stH, unit = unit)
}

private fun DrawScope.drawChar(
    viewer: ViewerViewModel,
    c: LoadedChar?,
    id: Int,
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
    val shown = resolve(c, id, play, clock) ?: return
    val f = shown.anim.frames.getOrNull(shown.anim.frameIndexAt(shown.tick, shown.loop)) ?: return
    // a escala é de quem é dono da animação (um alvo que usa a animação do atacante usa a escala dele)
    val sx = Placement.charScale(stW, shown.c.localCoordX, shown.c.size.xscale)
    val sy = Placement.charScale(stH, shown.c.localCoordY, shown.c.size.yscale)

    val cs = viewer.sprite(shown.spriteOwner, f.group, f.image)
    if (cs != null) {
        val r = Placement.sprite(px, py, facing, f, cs.info, cs.w, cs.h, sx, sy)
        drawSprite(cs, r, unit, f.blend, f.alphaSrc)
    }
    if (showBoxes) {
        for (b in f.clsn2) drawBox(Placement.box(px, py, facing, b, sx, sy), Color(0xFF4DA3FF), unit)
        for (b in f.clsn1) drawBox(Placement.box(px, py, facing, b, sx, sy), Color(0xFFFF5A5A), unit)
    }
}

/** Desenha o instante da gravação que corresponde ao relógio: cada lutador, helper e efeito do tick, do fundo para a frente. */
private fun DrawScope.drawTake(
    viewer: ViewerViewModel,
    tp: TakePlay,
    clock: Float,
    showBoxes: Boolean,
    stW: Int,
    stH: Int,
    unit: Float,
    ground: Float,
) {
    val total = tp.take.ticks
    if (total <= 0) return
    val raw = (clock - tp.start).toInt().coerceAtLeast(0)
    val tick = if (tp.repeat) raw % total else raw.coerceAtMost(total - 1)
    val frames = tp.take.byTick.getOrNull(tick) ?: return
    // sprpriority: quem tem o maior valor fica por cima; empate mantém o lado 1 na frente
    val ordered = frames.sortedWith(compareBy<SimFrame>({ it.prio }, { -it.ownSide }, { it.uid }))
    val dir = if (tp.mirror) -1 else 1
    for (f in ordered) {
        val ownerChar = (if (f.ownSide == 1) tp.atk else tp.tgt) ?: continue
        val animChar = (if (f.side == 1) tp.atk else tp.tgt) ?: continue
        val spriteOwner = if (f.side == 1) tp.atkOwner else tp.tgtOwner
        val anim = animChar.anims[f.anim] ?: continue
        val fr = anim.frames.getOrNull(anim.frameIndexAt(f.animTick, true)) ?: continue
        val px = stW / 2f + dir * f.x.toFloat() * stW / ownerChar.localCoordX.coerceAtLeast(1)
        val py = ground + f.y.toFloat() * stH / ownerChar.localCoordY.coerceAtLeast(1)
        val facing = f.facing * dir
        val sx = Placement.charScale(stW, animChar.localCoordX, animChar.size.xscale)
        val sy = Placement.charScale(stH, animChar.localCoordY, animChar.size.yscale)
        val cs = viewer.sprite(spriteOwner, fr.group, fr.image)
        if (cs != null) {
            val r = Placement.sprite(px, py, facing, fr, cs.info, cs.w, cs.h, sx, sy)
            drawSprite(cs, r, unit, fr.blend, fr.alphaSrc)
        }
        if (showBoxes) {
            for (b in fr.clsn2) drawBox(Placement.box(px, py, facing, b, sx, sy), Color(0xFF4DA3FF), unit)
            for (b in fr.clsn1) drawBox(Placement.box(px, py, facing, b, sx, sy), Color(0xFFFF5A5A), unit)
        }
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
