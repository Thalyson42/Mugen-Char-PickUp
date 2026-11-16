package com.ikemenselect.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ikemenselect.core.ByteArraySource
import com.ikemenselect.core.RandomAccessSource
import com.ikemenselect.core.ReadOnlyFs
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Acesso à pasta do Ikemen GO que o usuário liberou (Storage Access Framework).
 * Os arquivos são lidos/gravados em ISO-8859-1 para que qualquer byte do select.def
 * (acentos, BOM...) volte exatamente igual ao que foi lido.
 */
class SafTree(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : ReadOnlyFs {

    data class Node(val id: String, val name: String, val isDir: Boolean)

    private val idPrefix = if (rootId.endsWith(":")) rootId else "$rootId/"
    private val childCache = ConcurrentHashMap<String, List<Node>>()

    fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    fun children(parentId: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val out = ArrayList<Node>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(Node(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR))
            }
        }
        return out
    }

    private fun childrenCached(parentId: String): List<Node> =
        childCache[parentId] ?: children(parentId).also { childCache[parentId] = it }

    /** Esquece as listagens guardadas (chamar quando pastas podem ter sido criadas/removidas). */
    fun clearCache() = childCache.clear()

    /**
     * Resolve um caminho relativo à raiz ("data/ikemen1/select.def"), sem diferenciar maiúsculas.
     * Com [cached] = true as listagens de pasta são reaproveitadas (bem mais rápido ao abrir muitos personagens).
     */
    fun resolveNode(path: String, cached: Boolean = false): Node? {
        var cur = Node(rootId, "", true)
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            if (!cur.isDir) return null
            val list = if (cached) childrenCached(cur.id) else children(cur.id)
            cur = list.firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: return null
        }
        return cur
    }

    override fun exists(path: String): Boolean = resolveNode(path) != null

    override fun readText(path: String): String? =
        resolveNode(path)?.takeIf { !it.isDir }?.let { readNode(it.id) }

    fun readNode(id: String): String? =
        resolver.openInputStream(docUri(id))?.use { String(it.readBytes(), Charsets.ISO_8859_1) }

    fun writeNode(id: String, text: String) {
        val out = resolver.openOutputStream(docUri(id), "wt")
            ?: error("Não foi possível abrir o arquivo para escrita")
        out.use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
    }

    fun createFile(parentId: String, name: String): Node? {
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), "application/octet-stream", name)
            ?: return null
        return Node(DocumentsContract.getDocumentId(uri), name, false)
    }

    /**
     * Abre um arquivo grande (como um .sff) para leitura por posição, sem carregá-lo inteiro.
     * Se o provedor de arquivos não permitir acesso aleatório, lê o arquivo todo para a memória.
     */
    fun openSource(id: String): RandomAccessSource? {
        val uri = docUri(id)
        try {
            val pfd = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    val src = FdSource(pfd)
                    if (src.length > 0 && src.read(0, 1).isNotEmpty()) return src
                    src.close()
                } catch (e: Exception) {
                    runCatching { pfd.close() }
                }
            }
        } catch (e: Exception) {
            // cai para a leitura completa
        }
        return try {
            resolver.openInputStream(uri)?.use { ByteArraySource(it.readBytes()) }
        } catch (e: Exception) {
            null
        }
    }

    /** Caminho relativo à raiz de um documento escolhido pelo seletor de arquivos, ou null se estiver fora dela. */
    fun relativePathOf(documentUri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull() ?: return null
        return if (id.startsWith(idPrefix)) id.removePrefix(idPrefix) else null
    }
}

/** Leitura por posição sobre um descritor de arquivo (FileChannel.read(buffer, position) é seguro entre threads). */
private class FdSource(private val pfd: ParcelFileDescriptor) : RandomAccessSource {
    private val input = FileInputStream(pfd.fileDescriptor)
    private val channel = input.channel
    override val length: Long = channel.size()

    override fun read(position: Long, len: Int): ByteArray {
        if (position < 0 || position >= length || len <= 0) return ByteArray(0)
        val n = minOf(len.toLong(), length - position).toInt()
        val buf = ByteBuffer.allocate(n)
        var at = position
        while (buf.hasRemaining()) {
            val r = channel.read(buf, at)
            if (r < 0) break
            at += r
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { pfd.close() }
    }
}
