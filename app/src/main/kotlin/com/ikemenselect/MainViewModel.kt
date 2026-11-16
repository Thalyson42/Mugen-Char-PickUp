package com.ikemenselect

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ikemenselect.core.Candidate
import com.ikemenselect.core.Cell
import com.ikemenselect.core.CharFiles
import com.ikemenselect.core.GridOps
import com.ikemenselect.core.Locator
import com.ikemenselect.core.PngDecoder
import com.ikemenselect.core.Portrait
import com.ikemenselect.core.SelectDef
import com.ikemenselect.core.SffReader
import com.ikemenselect.core.isBlankCell
import com.ikemenselect.saf.SafTree
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Retrato (sprite 9000,0) de um personagem: ainda carregando, indisponível, ou pronto. */
sealed interface Face {
    object Loading : Face
    object Missing : Face
    class Image(val bitmap: ImageBitmap) : Face
}

/** PNG dentro de .sff (SFF v2, formatos 10/11/12) decodificado pelo Android. */
private val AndroidPng = PngDecoder { bytes ->
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@PngDecoder null
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val out = Portrait(bmp.width, bmp.height, px)
    bmp.recycle()
    out
}

data class UiState(
    val loading: Boolean = false,
    val ready: Boolean = false,
    val hasFolder: Boolean = false,
    val folderName: String = "",
    val selectPath: String = "",
    val rows: Int = 10,
    val columns: Int = 8,
    val cells: List<Cell?> = emptyList(),
    val selected: Int? = null,
    val canUndo: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Muda quando os retratos precisam ser relidos (nova pasta, personagens novos). */
    val portraitEpoch: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private class Loaded(
        val tree: SafTree,
        val selectPath: String,
        val selectNodeId: String,
        val original: String,
        val def: SelectDef,
        val charsName: String,
        val charsUri: Uri,
        val rows: Int,
        val columns: Int,
    )

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var loaded: Loaded? = null
    private var def: SelectDef? = null
    private var backupDone = false
    private val undoStack = ArrayDeque<List<Cell?>>()
    private val ioLock = Mutex()

    private val faces = ConcurrentHashMap<String, Face>()
    private val faceGate = Semaphore(3) // no máximo 3 .sff abertos ao mesmo tempo

    init {
        prefs.getString(KEY_TREE, null)?.let { load(Uri.parse(it)) }
    }

    fun charsFolderUri(): Uri? = loaded?.charsUri

    // ---------- retratos ----------

    /** Resposta imediata (sem I/O): o que já se sabe sobre o retrato desse personagem. */
    fun peekFace(entry: String): Face = faces[entry.lowercase()] ?: Face.Loading

    /** Carrega (ou devolve do cache) o retrato do personagem. */
    suspend fun face(entry: String): Face {
        val key = entry.lowercase()
        faces[key]?.let { return it }
        val l = loaded ?: return Face.Missing
        val result = faceGate.withPermit {
            withContext(Dispatchers.IO) { loadFace(l, entry) }
        }
        if (loaded === l) faces[key] = result // descarta se a pasta mudou enquanto carregava
        return result
    }

    private fun loadFace(l: Loaded, entry: String): Face {
        return try {
            val rel = CharFiles.defPath(entry) ?: return Face.Missing
            val defPath = "${l.charsName}/$rel"
            val defNode = l.tree.resolveNode(defPath, cached = true)?.takeIf { !it.isDir } ?: return Face.Missing
            val defText = l.tree.readNode(defNode.id) ?: return Face.Missing
            val sprite = CharFiles.spriteFile(defText) ?: return Face.Missing
            val sffPath = defPath.substringBeforeLast('/') + "/" + sprite
            val sffNode = l.tree.resolveNode(sffPath, cached = true)?.takeIf { !it.isDir } ?: return Face.Missing
            val portrait = l.tree.openSource(sffNode.id)?.use { SffReader.readPortrait(it, AndroidPng) }
                ?: return Face.Missing
            val bmp = Bitmap.createBitmap(portrait.argb, portrait.width, portrait.height, Bitmap.Config.ARGB_8888)
            Face.Image(bmp.asImageBitmap())
        } catch (e: Exception) {
            Face.Missing
        } catch (e: OutOfMemoryError) {
            Face.Missing
        }
    }

    /** Pastas de personagens podem ter mudado: esquece listagens e tenta de novo os que falharam. */
    private fun refreshFaces() {
        loaded?.tree?.clearCache()
        faces.values.removeAll { it === Face.Missing }
        _state.update { it.copy(portraitEpoch = it.portraitEpoch + 1) }
    }

    // ---------- carregar ----------

    fun onTreePicked(uri: Uri) {
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        load(uri)
    }

    fun reload() {
        prefs.getString(KEY_TREE, null)?.let { load(Uri.parse(it)) }
    }

    private fun load(uri: Uri) {
        _state.update { it.copy(loading = true, error = null, hasFolder = true, ready = false, selected = null) }
        viewModelScope.launch {
            try {
                val l = withContext(Dispatchers.IO) { openTree(uri) }
                loaded = l
                faces.clear()
                backupDone = false
                undoStack.clear()
                val cells = GridOps.normalize(l.def.cells, l.rows, l.columns)
                def = l.def.withCells(cells)
                _state.update {
                    it.copy(
                        loading = false, ready = true, hasFolder = true,
                        folderName = l.tree.rootId.substringAfterLast(':').substringAfterLast('/'),
                        selectPath = l.selectPath, rows = l.rows, columns = l.columns,
                        cells = cells, selected = null, canUndo = false, error = null,
                        portraitEpoch = it.portraitEpoch + 1,
                    )
                }
            } catch (e: Exception) {
                loaded = null
                def = null
                _state.update {
                    it.copy(loading = false, ready = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    private fun openTree(uri: Uri): Loaded {
        val resolver = getApplication<Application>().contentResolver
        var tree = SafTree(resolver, uri)
        if (!(tree.exists("data") && tree.exists("chars"))) {
            // às vezes o usuário escolhe a pasta "pai"; procura um nível abaixo
            val sub = tree.children(tree.rootId).filter { it.isDir }.firstOrNull {
                val s = SafTree(resolver, uri, it.id)
                s.exists("data") && s.exists("chars")
            } ?: error("Essa pasta não parece ser a raiz do Ikemen GO (precisa conter as pastas \"data\" e \"chars\").")
            tree = SafTree(resolver, uri, sub.id)
        }
        val located = Locator.locate(tree) ?: error("Não encontrei o select.def dentro de \"data\".")
        val selectNode = tree.resolveNode(located.selectPath) ?: error("select.def não encontrado.")
        val text = tree.readNode(selectNode.id) ?: error("Não foi possível ler o select.def.")
        val parsed = SelectDef.parse(text)
        if (!parsed.hasCharactersSection) error("O select.def não tem a seção [Characters].")
        val chars = tree.resolveNode("chars") ?: error("Pasta chars não encontrada.")

        val rows = prefs.getInt(KEY_ROWS, 0).takeIf { it > 0 } ?: located.rows?.takeIf { it > 0 } ?: 10
        val cols = prefs.getInt(KEY_COLS, 0).takeIf { it > 0 } ?: located.columns?.takeIf { it > 0 } ?: 8
        return Loaded(tree, located.selectPath, selectNode.id, text, parsed, chars.name, tree.docUri(chars.id), rows, cols)
    }

    // ---------- interação com o grid ----------

    fun select(index: Int) = _state.update { it.copy(selected = if (it.selected == index) null else index) }

    fun swap(from: Int, to: Int) {
        val cur = _state.value.cells
        if (from == to) return
        commit(GridOps.swap(cur, from, to), selected = to)
    }

    fun remove(index: Int) {
        val cur = _state.value.cells
        val cell = cur.getOrNull(index)
        if (cell.isBlankCell()) return
        commit(GridOps.remove(cur, index), msg = "Removido: ${cell?.label}", selected = index)
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        apply(prev, "Desfeito", null, save = true)
    }

    /** Adiciona o .def escolhido no seletor de arquivos na posição [index]. */
    fun addManual(uri: Uri, index: Int) {
        val l = loaded ?: return
        val d = def ?: return
        val rel = l.tree.relativePathOf(uri)
        val prefix = l.charsName + "/"
        if (rel == null || !rel.startsWith(prefix, ignoreCase = true)) {
            say("Escolha um arquivo .def dentro da pasta ${l.charsName} do Ikemen GO.")
            return
        }
        val entry = GridOps.entryFromRelativePath(rel.substring(prefix.length))
        if (entry == null) {
            say("Selecione o arquivo .def do personagem.")
            return
        }
        val cell = Cell.forEntry(entry)
        if (cell.key in d.knownKeys()) {
            say("${cell.label} já está no select.def.")
            return
        }
        val cur = _state.value.cells
        val target = if (cur.getOrNull(index).isBlankCell() && index in cur.indices) index
        else GridOps.firstBlank(cur).takeIf { it >= 0 } ?: cur.size
        refreshFaces() // a pasta do personagem pode ser nova
        commit(GridOps.place(cur, target, cell), msg = "Adicionado: ${cell.label}", selected = target)
    }

    /** Botão "Update char list". */
    fun updateCharList() {
        val l = loaded ?: return
        val d = def ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { scanChars(l) }
                val result = GridOps.addMissing(_state.value.cells, found, d.knownKeys())
                refreshFaces() // personagens novos: a listagem em cache ficou desatualizada
                _state.update { it.copy(loading = false) }
                if (result.added.isEmpty()) {
                    say("Nenhum personagem novo em ${l.charsName}.")
                } else {
                    val names = result.added.take(5).joinToString(", ") { it.folder }
                    val more = if (result.added.size > 5) " e mais ${result.added.size - 5}" else ""
                    commit(result.cells, msg = "${result.added.size} adicionado(s): $names$more")
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Falha ao ler a pasta chars: ${e.message}") }
            }
        }
    }

    private fun scanChars(l: Loaded): List<Candidate> {
        val chars = l.tree.resolveNode("chars") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (dir in l.tree.children(chars.id).filter { it.isDir }) {
            val files = l.tree.children(dir.id).filter { !it.isDir }.map { it.name }
            GridOps.pickDef(dir.name, files)?.let { out.add(Candidate(dir.name, it)) }
        }
        return out
    }

    fun setGridSize(rows: Int, columns: Int) {
        if (rows < 1 || columns < 1) return
        prefs.edit().putInt(KEY_ROWS, rows).putInt(KEY_COLS, columns).apply()
        val cells = GridOps.normalize(_state.value.cells, rows, columns)
        def = def?.withCells(cells)
        _state.update { it.copy(rows = rows, columns = columns, cells = cells, selected = null) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearError() = _state.update { it.copy(error = null) }
    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    // ---------- aplicar e salvar ----------

    private fun commit(newCells: List<Cell?>, msg: String? = null, selected: Int? = _state.value.selected) {
        undoStack.addLast(_state.value.cells)
        if (undoStack.size > 50) undoStack.removeFirst()
        apply(newCells, msg, selected, save = true)
    }

    private fun apply(cells: List<Cell?>, msg: String?, selected: Int?, save: Boolean) {
        val s = _state.value
        val norm = GridOps.normalize(cells, s.rows, s.columns)
        def = def?.withCells(norm)
        _state.update { it.copy(cells = norm, selected = selected, canUndo = undoStack.isNotEmpty(), message = msg ?: it.message) }
        if (save) save()
    }

    private fun save() {
        val l = loaded ?: return
        val d = def ?: return
        viewModelScope.launch(Dispatchers.IO) {
            ioLock.withLock {
                try {
                    if (!backupDone) {
                        backup(l)
                        backupDone = true
                    }
                    l.tree.writeNode(l.selectNodeId, d.write())
                } catch (e: Exception) {
                    _state.update { it.copy(error = "Falha ao salvar o select.def: ${e.message}") }
                }
            }
        }
    }

    /** Guarda o select.def original como select.def.bak antes da primeira alteração da sessão. */
    private fun backup(l: Loaded) {
        val dir = l.tree.resolveNode(l.selectPath.substringBeforeLast('/', "")) ?: return
        val name = l.selectPath.substringAfterLast('/') + ".bak"
        val id = l.tree.children(dir.id).firstOrNull { it.name.equals(name, true) }?.id
            ?: l.tree.createFile(dir.id, name)?.id
            ?: return
        l.tree.writeNode(id, l.original)
    }

    private companion object {
        const val KEY_TREE = "tree"
        const val KEY_ROWS = "rows"
        const val KEY_COLS = "columns"
    }
}
