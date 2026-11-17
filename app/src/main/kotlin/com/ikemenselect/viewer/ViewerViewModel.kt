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
import com.ikemenselect.core.SffFile
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
)

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
                _ui.update {
                    val n = if (owner == Owner.P1) it.copy(p1 = loaded) else it.copy(p2 = loaded)
                    n.copy(busy = false, version = it.version + 1)
                }
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
        cache.keys.removeAll { (it shr 40) == prefix }
        missing.removeAll { (it shr 40) == prefix }
    }

    private fun closeAll() {
        val u = _ui.value
        runCatching { u.p1?.sff?.close() }
        runCatching { u.p2?.sff?.close() }
        runCatching { u.stage?.sff?.close() }
        cache.clear()
        missing.clear()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        closeAll()
    }
}
