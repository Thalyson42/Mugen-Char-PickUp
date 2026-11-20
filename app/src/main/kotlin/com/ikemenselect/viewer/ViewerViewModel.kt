package com.ikemenselect.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ikemenselect.AndroidPng
import com.ikemenselect.core.Animation
import com.ikemenselect.core.AirParser
import com.ikemenselect.core.CharDef
import com.ikemenselect.core.CharFiles
import com.ikemenselect.core.CharSize
import com.ikemenselect.core.MovePlan
import com.ikemenselect.core.CatalogEntry
import com.ikemenselect.core.MoveCatalog
import com.ikemenselect.core.MovePlanner
import com.ikemenselect.core.PlanOptions
import com.ikemenselect.core.StateBook
import com.ikemenselect.core.Pcm
import com.ikemenselect.core.SffFile
import com.ikemenselect.core.SndFile
import com.ikemenselect.core.SndRef
import com.ikemenselect.core.sim.Sim
import com.ikemenselect.core.sim.SimChar
import com.ikemenselect.core.sim.Assumption
import com.ikemenselect.core.sim.Scenario
import com.ikemenselect.core.sim.SimRunner
import com.ikemenselect.core.sim.SimTake
import com.ikemenselect.core.SffReader
import com.ikemenselect.core.SpriteInfo
import com.ikemenselect.core.StageDef
import com.ikemenselect.saf.SafTree
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O que o Character View recebe da tela principal. */
class ViewerSource(val tree: SafTree, val charsName: String, val chars: List<CharOption>)

data class CharOption(val entry: String, val label: String)
data class StageOption(val path: String, val label: String)

/** Personagem carregado: animações lidas do .air e o .sff aberto (sprites são decodificados sob demanda). */
class LoadedChar(
    val option: CharOption,
    val name: String,
    val localCoordX: Int,
    val localCoordY: Int,
    val size: CharSize,
    val anims: Map<Int, Animation>,
    val sff: SffFile?,
    /** Pasta do personagem (relativa à raiz) e arquivos do [Files] do .def, para ler os estados depois. */
    val dir: String,
    val files: Map<String, String>,
) {
    /** Estados e comandos; null enquanto estão sendo lidos. */
    @Volatile var book: StateBook? = null

    /** Lista de golpes já planejada (com as continuações juntas); null enquanto está sendo calculada. */
    @Volatile var catalog: List<CatalogEntry>? = null

    /** Constantes do personagem (`size.xscale`, `movement.yaccel`...) lidas dos mesmos arquivos de estado. */
    @Volatile var consts: Map<String, Double> = emptyMap()

    /** .snd do personagem, aberto na primeira vez que um som é pedido. */
    @Volatile var snd: SndFile? = null
    @Volatile var sndTried = false
}

class LoadedStage(
    val option: StageOption,
    val def: StageDef,
    val sff: SffFile?,
)

/** Sprite pronto para desenhar. [info] traz o eixo; [w]/[h] o tamanho real decodificado. */
class CachedSprite(val image: ImageBitmap, val info: SpriteInfo, val w: Int, val h: Int) {
    val bytes: Long get() = w.toLong() * h * 4
}

data class ViewerUi(
    val chars: List<CharOption> = emptyList(),
    val stages: List<StageOption> = emptyList(),
    val p1: LoadedChar? = null,
    val p2: LoadedChar? = null,
    val stage: LoadedStage? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Muda quando algo carregado muda; a tela usa para recomeçar a animação. */
    val version: Int = 0,
)

object Owner {
    const val P1 = 1
    const val P2 = 2
    const val STAGE = 3
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(ViewerUi())
    val ui: StateFlow<ViewerUi> = _ui.asStateFlow()

    private var source: ViewerSource? = null

    // O SffFile não é seguro entre threads: toda decodificação passa por uma única thread.
    private val decoder: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val cache = ConcurrentHashMap<Long, CachedSprite>()
    private val missing: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private fun key(owner: Int, group: Int, number: Int): Long =
        (owner.toLong() shl 40) or ((group.toLong() and 0xFFFFF) shl 20) or (number.toLong() and 0xFFFFF)

    // ---------------------------------------------------------------- abrir

    /** Chamado ao entrar no Character View. Só recomeça se a pasta do jogo mudou. */
    fun open(src: ViewerSource) {
        val same = source?.tree?.rootId == src.tree.rootId && source?.tree?.treeUri == src.tree.treeUri
        source = src
        if (same) {
            _ui.update { it.copy(chars = src.chars) }
            return
        }
        closeAll()
        _ui.value = ViewerUi(chars = src.chars, busy = true)
        viewModelScope.launch {
            val stages = withContext(Dispatchers.IO) { scanStages(src.tree) }
            _ui.update { it.copy(stages = stages, busy = false) }
        }
    }

    private fun scanStages(tree: SafTree): List<StageOption> {
        val root = tree.resolveNode("stages") ?: return emptyList()
        val out = ArrayList<StageOption>()
        for (n in tree.children(root.id)) {
            if (!n.isDir && n.name.endsWith(".def", ignoreCase = true)) {
                out.add(StageOption("${root.name}/${n.name}", n.name.dropLast(4)))
            } else if (n.isDir) {
                for (c in tree.children(n.id)) {
                    if (!c.isDir && c.name.endsWith(".def", ignoreCase = true)) {
                        out.add(StageOption("${root.name}/${n.name}/${c.name}", "${n.name}/${c.name.dropLast(4)}"))
                    }
                }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    // ---------------------------------------------------------------- escolher

    fun pickChar(owner: Int, option: CharOption) {
        val src = source ?: return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { loadChar(src, option) }
                clearOwner(owner)
                val old = if (owner == Owner.P1) _ui.value.p1 else _ui.value.p2
                runCatching { old?.sff?.close() }
                runCatching { old?.snd?.close() }
                _ui.update {
                    val n = if (owner == Owner.P1) it.copy(p1 = loaded) else it.copy(p2 = loaded)
                    n.copy(busy = false, version = it.version + 1)
                }
                loadBook(loaded)
                // já deixa pronta a animação parada (0), que é a que aparece primeiro
                loaded.anims[0]?.let { ensureAnim(owner, loaded, it) }
                _ui.update { it.copy(version = it.version + 1) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = "${option.label}: ${e.message ?: e}") }
            }
        }
    }

    fun pickStage(option: StageOption?) {
        val src = source ?: return
        if (option == null) {
            clearOwner(Owner.STAGE)
            runCatching { _ui.value.stage?.sff?.close() }
            _ui.update { it.copy(stage = null, version = it.version + 1) }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { loadStage(src, option) }
                clearOwner(Owner.STAGE)
                runCatching { _ui.value.stage?.sff?.close() }
                _ui.update { it.copy(stage = loaded, version = it.version + 1) }
                preloadStage(loaded)
                _ui.update { it.copy(busy = false, version = it.version + 1) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = "${option.label}: ${e.message ?: e}") }
            }
        }
    }

    // ---------------------------------------------------------------- carregar arquivos

    private fun loadChar(src: ViewerSource, option: CharOption): LoadedChar {
        val tree = src.tree
        val rel = CharFiles.defPath(option.entry) ?: error("entrada inválida no select.def")
        val defPath = "${src.charsName}/$rel"
        val defNode = tree.resolveNode(defPath, cached = true)?.takeIf { !it.isDir } ?: error("não achei o .def")
        val def = CharDef.parse(tree.readNode(defNode.id) ?: error("não consegui ler o .def"))
        val dir = defPath.substringBeforeLast('/')

        fun file(name: String?) =
            if (name == null) null else tree.resolveNode("$dir/$name", cached = true)?.takeIf { !it.isDir }

        val airNode = file(def.anim) ?: error("o .def não aponta para um .air existente (anim = ${def.anim})")
        val anims = AirParser.parse(tree.readNode(airNode.id) ?: error("não consegui ler o .air"))
        if (anims.isEmpty()) error("o .air não tem animações")

        val sffNode = file(def.sprite) ?: error("não achei o .sff (sprite = ${def.sprite})")
        val sff = tree.openSource(sffNode.id)?.let { SffReader.open(it, AndroidPng) } ?: error("o .sff não pôde ser aberto")

        val size = file(def.cns)?.let { tree.readNode(it.id) }?.let { CharSize.parse(it) } ?: CharSize.DEFAULT
        return LoadedChar(
            option, def.name.ifEmpty { option.label }, def.localCoordX, def.localCoordY, size, anims, sff,
            dir, def.files,
        )
    }

    private fun loadStage(src: ViewerSource, option: StageOption): LoadedStage {
        val tree = src.tree
        val node = tree.resolveNode(option.path)?.takeIf { !it.isDir } ?: error("não achei o cenário")
        val def = StageDef.parse(tree.readNode(node.id) ?: error("não consegui ler o cenário"))
        val dir = option.path.substringBeforeLast('/')
        val spr = def.sprite
        val sffNode = spr?.let {
            tree.resolveNode("$dir/$it", cached = true) ?: tree.resolveNode("stages/$it", cached = true)
                ?: tree.resolveNode("data/$it", cached = true)
        }?.takeIf { !it.isDir }
        val sff = sffNode?.let { n -> tree.openSource(n.id)?.let { SffReader.open(it, AndroidPng) } }
        return LoadedStage(option, def, sff)
    }

    // ---------------------------------------------------------------- estados e golpes

    /** Lê os arquivos de estado do personagem em segundo plano; quando termina a tela é avisada. */
    private fun loadBook(c: LoadedChar) {
        val src = source ?: return
        viewModelScope.launch {
            val (book, consts) = withContext(Dispatchers.IO) {
                try {
                    readBook(src.tree, c)
                } catch (e: Exception) {
                    StateBook.EMPTY to emptyMap<String, Double>()
                }
            }
            c.consts = consts
            c.book = book
            _ui.update { it.copy(version = it.version + 1) }
            // analisa os golpes fora da thread principal: são centenas de planos
            c.catalog = withContext(Dispatchers.Default) {
                try {
                    MoveCatalog.build(book, isolated = true) { c.anims[it] }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _ui.update { it.copy(version = it.version + 1) }
        }
    }

    /** Ordem do jogo: stcommon, cmd, cns, st, st1..st9 (o último arquivo vence). */
    private fun readBook(tree: SafTree, c: LoadedChar): Pair<StateBook, Map<String, Double>> {
        val names = ArrayList<String>()
        c.files["stcommon"]?.let { names.add(it) }
        c.files["cmd"]?.let { names.add(it) }
        c.files["cns"]?.let { names.add(it) }
        for (k in listOf("st") + (1..9).map { "st$it" }) c.files[k]?.let { names.add(it) }
        val texts = ArrayList<String>()
        for (name in names.distinct()) {
            val node = tree.resolveNode("${c.dir}/$name", cached = true)
                ?: tree.resolveNode("data/$name", cached = true)
            if (node != null && !node.isDir) tree.readNode(node.id)?.let { texts.add(it) }
        }
        return StateBook.parse(texts) to Sim.parseConsts(texts)
    }

    /** Planeja o golpe do estado [stateNo] de [c]. [assumeHit]: supõe que acertou (há um adversário). */
    suspend fun planMove(c: LoadedChar, stateNo: Int, assumeHit: Boolean, entry: CatalogEntry? = null, target: LoadedChar? = null): MovePlan? =
        withContext(Dispatchers.Default) {
            val book = c.book ?: return@withContext null
            val helper = c.catalog?.firstOrNull { it.stateNo == stateNo }?.helper == true
            MovePlanner.plan(book, stateNo, { c.anims[it] }, PlanOptions(assumeHit = assumeHit, asHelper = helper, singleState = true, helperId = entry?.helperId, vars = entry?.vars.orEmpty(), rootVars = entry?.rootVars.orEmpty(), targetAnims = target?.anims?.keys))
        }

    /**
     * Roda o simulador com o estado [stateNo] de [c] (ou o helper da variante em [entry]) contra [other] e grava o resultado.
     * Devolve null enquanto os estados do personagem ainda estão sendo lidos.
     */
    suspend fun record(c: LoadedChar, other: LoadedChar?, entry: CatalogEntry?, stateNo: Int): SimTake? =
        withContext(Dispatchers.Default) {
            val book = c.book ?: return@withContext null
            val p1 = SimChar(book, { c.anims[it] }, c.consts, c.name)
            val p2 = other?.book?.let { b -> SimChar(b, { other.anims[it] }, other.consts) }
            val helperId = if (entry?.helper == true) (entry.helperId ?: 0) else null
            // primeiro tenta chegar ao estado do jeito do jogo: apertando o comando e assumindo os interruptores que faltarem
            val initial = entry?.rootVars.orEmpty().map { (k, v) -> Assumption("root", 'v', k, v.toDouble()) } +
                entry?.vars.orEmpty().map { (k, v) -> Assumption(if (helperId == null) "root" else "h$helperId", 'v', k, v.toDouble()) }
            Scenario.record(p1, p2, stateNo, helperId, initial, coordY = c.localCoordY.toDouble(), budgetMs = 12_000)?.let { return@withContext it }
            SimRunner.record(
                p1, p2, stateNo, helperId,
                vars = entry?.vars.orEmpty(), rootVars = entry?.rootVars.orEmpty(),
                coordY = c.localCoordY.toDouble(),
            )
        }

    // ---------------------------------------------------------------- sons

    private val sounds = SoundPlayer()
    private val pcmCache = ConcurrentHashMap<Long, Pcm>()
    private var commonSnd: SndFile? = null
    private var commonTried = false

    private fun pcmKey(owner: Int, group: Int, number: Int): Long =
        (owner.toLong() shl 48) or ((group.toLong() and 0xFFFFFF) shl 24) or (number.toLong() and 0xFFFFFF)

    private fun openSnd(tree: SafTree, paths: List<String>): SndFile? {
        for (path in paths) {
            val node = tree.resolveNode(path, cached = true)?.takeIf { !it.isDir } ?: continue
            return try {
                tree.openSource(node.id)?.let { SndFile.open(it) }
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    private fun ownSnd(c: LoadedChar): SndFile? = synchronized(c) {
        if (!c.sndTried) {
            c.sndTried = true
            val tree = source?.tree
            val name = c.files["sound"]
            if (tree != null && name != null) c.snd = openSnd(tree, listOf("${c.dir}/$name", "data/$name"))
        }
        c.snd
    }

    /** Sons comuns do jogo (prefixo F: golpes que acertam, quedas...). Opcional: se não achar, esses ficam mudos. */
    private fun commonSound(): SndFile? = synchronized(this) {
        if (!commonTried) {
            commonTried = true
            val tree = source?.tree
            if (tree != null) {
                commonSnd = openSnd(
                    tree,
                    listOf("data/fight.snd", "sound/fight.snd", "data/common.snd", "sound/common.snd", "data/ikemen1/fight.snd"),
                )
            }
        }
        commonSnd
    }

    /** Lê os sons que o golpe vai usar, para tocá-los na hora certa sem esperar o arquivo. */
    suspend fun prepareSounds(ownerId: Int, c: LoadedChar, refs: Collection<SndRef>) {
        withContext(Dispatchers.IO) {
            if (pcmCache.values.sumOf { it.data.size.toLong() } > 64L * 1024 * 1024) pcmCache.clear()
            for (r in refs.distinct()) {
                val owner = if (r.common) 0 else ownerId
                val k = pcmKey(owner, r.group, r.number)
                if (pcmCache.containsKey(k)) continue
                val file = if (r.common) commonSound() else ownSnd(c)
                val pcm = try {
                    file?.pcm(r.group, r.number)
                } catch (e: Throwable) {
                    null
                }
                if (pcm != null) pcmCache[k] = pcm
            }
        }
    }

    fun playSound(ownerId: Int, ref: SndRef) {
        val pcm = pcmCache[pcmKey(if (ref.common) 0 else ownerId, ref.group, ref.number)] ?: return
        sounds.play(pcm)
    }

    fun stopSounds() = sounds.stopAll()

    // ---------------------------------------------------------------- sprites

    /** Sprite já decodificado (sem I/O) ou null se ainda não carregou / não existe. */
    fun sprite(owner: Int, group: Int, number: Int): CachedSprite? = cache[key(owner, group, number)]

    /** Decodifica todos os sprites da animação. Só volta quando estiverem prontos. */
    suspend fun ensureAnim(owner: Int, c: LoadedChar, anim: Animation) {
        val sff = c.sff ?: return
        val wanted = anim.frames.map { it.group to it.image }.distinct()
        decodeAll(owner, sff, wanted)
        // a animação parada (0) fica sempre guardada: é para ela que o personagem volta depois de cada golpe
        val idle = c.anims[0]?.frames?.map { it.group to it.image }.orEmpty()
        trim(owner, wanted + idle)
    }

    /** Decodifica os sprites de várias animações de uma vez e só depois limpa o que não é usado. */
    suspend fun ensureAnims(owner: Int, c: LoadedChar, list: Collection<Animation>) {
        val sff = c.sff ?: return
        val wanted = list.flatMap { a -> a.frames.map { it.group to it.image } }.distinct()
        decodeAll(owner, sff, wanted)
        val idle = c.anims[0]?.frames?.map { it.group to it.image }.orEmpty()
        trim(owner, wanted + idle)
    }

    private suspend fun preloadStage(st: LoadedStage) {
        val sff = st.sff ?: return
        val wanted = LinkedHashSet<Pair<Int, Int>>()
        for (l in st.def.layers) {
            if (l.type == "anim") {
                st.def.animations[l.actionNo]?.frames?.forEach { wanted.add(it.group to it.image) }
            } else if (l.type != "dummy") {
                wanted.add(l.spriteGroup to l.spriteNo)
            }
        }
        decodeAll(Owner.STAGE, sff, wanted.toList())
    }

    private suspend fun decodeAll(owner: Int, sff: SffFile, wanted: List<Pair<Int, Int>>) {
        withContext(decoder) {
            for ((g, n) in wanted) {
                val k = key(owner, g, n)
                if (cache.containsKey(k) || missing.contains(k)) continue
                val info = sff.info(g, n)
                val p = if (info == null) null else sff.decode(g, n)
                if (p == null || info == null) {
                    missing.add(k)
                    continue
                }
                try {
                    val bmp = Bitmap.createBitmap(p.argb, p.width, p.height, Bitmap.Config.ARGB_8888)
                    cache[k] = CachedSprite(bmp.asImageBitmap(), info, p.width, p.height)
                } catch (e: OutOfMemoryError) {
                    missing.add(k)
                }
            }
        }
    }

    /** Se os sprites desse dono passarem de ~48 MB, descarta os que não são da animação atual. */
    private fun trim(owner: Int, keep: List<Pair<Int, Int>>) {
        val prefix = owner.toLong()
        val mine = cache.entries.filter { (it.key shr 40) == prefix }
        if (mine.sumOf { it.value.bytes } <= 48L * 1024 * 1024) return
        val keepKeys = keep.map { key(owner, it.first, it.second) }.toSet()
        for (e in mine) if (e.key !in keepKeys) cache.remove(e.key)
    }

    private fun clearOwner(owner: Int) {
        val prefix = owner.toLong()
        pcmCache.keys.removeAll { (it shr 48) == prefix }
        sounds.stopAll()
        cache.keys.removeAll { (it shr 40) == prefix }
        missing.removeAll { (it shr 40) == prefix }
    }

    private fun closeAll() {
        val u = _ui.value
        runCatching { u.p1?.sff?.close() }
        runCatching { u.p2?.sff?.close() }
        runCatching { u.stage?.sff?.close() }
        runCatching { u.p1?.snd?.close() }
        runCatching { u.p2?.snd?.close() }
        runCatching { commonSnd?.close() }
        commonSnd = null
        commonTried = false
        pcmCache.clear()
        sounds.stopAll()
        cache.clear()
        missing.clear()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        closeAll()
    }
}
