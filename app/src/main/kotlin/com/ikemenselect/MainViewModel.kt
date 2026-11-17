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
import com.ikemenselect.core.CellKind
import com.ikemenselect.core.CharFiles
import com.ikemenselect.core.GridOps
import com.ikemenselect.core.Locator
import com.ikemenselect.core.PngDecoder
import com.ikemenselect.core.Portrait
import com.ikemenselect.core.SelectDef
import com.ikemenselect.core.SffReader
import com.ikemenselect.core.SystemDefEdit
import com.ikemenselect.core.isBlankCell
import com.ikemenselect.saf.SafTree
import com.ikemenselect.viewer.CharOption
import com.ikemenselect.viewer.ViewerSource
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
    class Image(val bitmap: ImageBitmap, val info: String = "") : Face
}

/** PNG dentro de .sff (SFF v2, formatos 10/11/12) decodificado pelo Android. */
internal val AndroidPng = PngDecoder { bytes ->
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
    val systemPath: String = "",
    /** O arquivo em uso foi escolhido à mão (Menu → Escolher select.def / system.def). */
    val selectManual: Boolean = false,
    val systemManual: Boolean = false,
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
        val systemPath: String?,
        val selectManual: Boolean = false,
        val systemManual: Boolean = false,
        val warnings: List<String> = emptyList(),
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

    /** O que o Character View precisa: a pasta aberta e os personagens do select.def (na ordem do grid). */
    fun viewerSource(): ViewerSource? {
        val l = loaded ?: return null
        val chars = _state.value.cells
            .filterNotNull()
            .filter { (it.kind == CellKind.CHAR || it.kind == CellKind.SLOT) && it.entry != null }
            .map { CharOption(it.entry!!, it.label.ifEmpty { it.entry!! }) }
            .distinctBy { it.entry.lowercase() }
        return ViewerSource(l.tree, l.charsName, chars)
    }

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
            val portrait = l.tree.openSource(sffNode.id)?.use { SffReader.readSelectPortrait(it, AndroidPng) }
                ?: return Face.Missing
            var bmp = Bitmap.createBitmap(portrait.argb, portrait.width, portrait.height, Bitmap.Config.ARGB_8888)
            val big = maxOf(bmp.width, bmp.height)
            if (big > 192) { // retratos enormes: reduz para não gastar memória
                val k = 192f / big
                bmp = Bitmap.createScaledBitmap(
                    bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true,
                )
            }
            Face.Image(bmp.asImageBitmap(), portrait.info)
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
        val changed = prefs.getString(KEY_TREE, null) != uri.toString()
        val e = prefs.edit().putString(KEY_TREE, uri.toString())
        // As escolhas manuais são caminhos dentro da pasta antiga: não valem para outra pasta.
        if (changed) e.remove(KEY_SELECT).remove(KEY_SYSTEM)
        e.apply()
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
                systemBackupDone = false
                undoStack.clear()
                val cells = GridOps.normalize(l.def.cells, l.rows, l.columns)
                def = l.def.withCells(cells)
                _state.update {
                    it.copy(
                        loading = false, ready = true, hasFolder = true,
                        folderName = l.tree.rootId.substringAfterLast(':').substringAfterLast('/'),
                        selectPath = l.selectPath, systemPath = l.systemPath ?: "",
                        selectManual = l.selectManual, systemManual = l.systemManual,
                        message = l.warnings.joinToString(" ").ifEmpty { it.message },
                        rows = l.rows, columns = l.columns,
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
        lastTree = tree // guardado antes de procurar os arquivos: o seletor manual precisa dele se a busca falhar
        val located = Locator.locate(tree, prefs.getString(KEY_SELECT, null), prefs.getString(KEY_SYSTEM, null))
            ?: error("Não encontrei o select.def dentro de \"data\". Use Menu → Escolher select.def.")
        val selectNode = tree.resolveNode(located.selectPath) ?: error("select.def não encontrado.")
        val text = tree.readNode(selectNode.id) ?: error("Não foi possível ler o select.def.")
        val parsed = SelectDef.parse(text)
        if (!parsed.hasCharactersSection) error("O select.def não tem a seção [Characters].")
        val chars = tree.resolveNode("chars") ?: error("Pasta chars não encontrada.")

        // O que vale é o system.def do jogo; o valor guardado no app só entra se não houver system.def.
        val rows = located.rows?.takeIf { it > 0 } ?: prefs.getInt(KEY_ROWS, 0).takeIf { it > 0 } ?: 10
        val cols = located.columns?.takeIf { it > 0 } ?: prefs.getInt(KEY_COLS, 0).takeIf { it > 0 } ?: 8
        return Loaded(
            tree, located.selectPath, selectNode.id, text, parsed, chars.name, tree.docUri(chars.id),
            rows, cols, located.systemDefPath,
            located.selectManual, located.systemManual, located.warnings,
        )
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
        saveGridSizeToSystemDef(rows, columns)
    }

    private var systemBackupDone = false

    // ---------- escolha manual de select.def / system.def ----------

    @Volatile private var lastTree: SafTree? = null

    /** Pasta data/ do jogo, para o seletor de arquivos já abrir nela. */
    fun dataFolderUri(): Uri? = lastTree?.let { t -> t.resolveNode("data")?.let { t.docUri(it.id) } }

    fun pickSelectDef(uri: Uri) = pickManual(uri, KEY_SELECT, isSelect = true)

    fun pickSystemDef(uri: Uri) = pickManual(uri, KEY_SYSTEM, isSelect = false)

    /** Apaga as escolhas manuais e volta a detectar sozinho. */
    fun useAutoDetection() {
        prefs.edit().remove(KEY_SELECT).remove(KEY_SYSTEM).apply()
        reload()
    }

    private fun pickManual(uri: Uri, key: String, isSelect: Boolean) {
        val tree = lastTree ?: return
        viewModelScope.launch {
            val (problem, rel) = withContext(Dispatchers.IO) { checkManual(tree, uri, isSelect) }
            if (problem != null || rel == null) {
                say(problem ?: "Arquivo inválido.")
                return@launch
            }
            prefs.edit().putString(key, rel).apply() // guardado: o app usa este arquivo sempre que abrir
            reload()
        }
    }

    /** Confere se o arquivo escolhido serve, para uma escolha errada não travar o app a cada abertura. */
    private fun checkManual(tree: SafTree, uri: Uri, isSelect: Boolean): Pair<String?, String?> {
        val rel = tree.relativePathOf(uri) ?: return "Escolha um arquivo dentro da pasta do Ikemen GO." to null
        if (!rel.endsWith(".def", ignoreCase = true)) return "Esse arquivo não é um .def." to null
        val text = tree.readText(rel) ?: return "Não consegui ler esse arquivo." to null
        if (isSelect) {
            if (!SelectDef.parse(text).hasCharactersSection) return "Esse arquivo não tem a seção [Characters]." to null
        } else {
            val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            val looksLikeSystem = Regex("""^\s*\[\s*(files|select info)\s*]""", opts).containsMatchIn(text)
            if (!looksLikeSystem) return "Esse arquivo não parece um system.def (sem [Files] nem [Select Info])." to null
        }
        return null to rel
    }

    /** Grava rows/columns na seção [Select Info] do system.def do motif, para o jogo usar o mesmo tamanho. */
    private fun saveGridSizeToSystemDef(rows: Int, columns: Int) {
        val l = loaded ?: return
        val path = l.systemPath
        if (path == null) {
            say("Grid alterado só no app: não achei o system.def do jogo.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            ioLock.withLock {
                try {
                    val node = l.tree.resolveNode(path) ?: error("system.def não encontrado")
                    val old = l.tree.readNode(node.id) ?: error("não foi possível ler o system.def")
                    val updated = SystemDefEdit.setGridSize(old, rows, columns)
                    if (updated != old) {
                        if (!systemBackupDone) {
                            backupFile(l.tree, path, old)
                            systemBackupDone = true
                        }
                        l.tree.writeNode(node.id, updated)
                    }
                    say("Grid ${rows}×${columns} gravado em $path")
                } catch (e: Exception) {
                    _state.update { it.copy(error = "Falha ao gravar o system.def: ${e.message}") }
                }
            }
        }
    }

    /** Guarda uma cópia (arquivo.bak) ao lado do original antes da primeira alteração da sessão. */
    private fun backupFile(tree: SafTree, path: String, content: String) {
        val dir = tree.resolveNode(path.substringBeforeLast('/', "")) ?: return
        val name = path.substringAfterLast('/') + ".bak"
        val id = tree.children(dir.id).firstOrNull { it.name.equals(name, true) }?.id
            ?: tree.createFile(dir.id, name)?.id
            ?: return
        tree.writeNode(id, content)
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
        const val KEY_SELECT = "select_override"
        const val KEY_SYSTEM = "system_override"
    }
}
