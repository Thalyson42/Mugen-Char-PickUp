package com.ikemenselect.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ikemenselect.core.ReadOnlyFs

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

    /** Resolve um caminho relativo à raiz ("data/ikemen1/select.def"), sem diferenciar maiúsculas. */
    fun resolveNode(path: String): Node? {
        var cur = Node(rootId, "", true)
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            if (!cur.isDir) return null
            cur = children(cur.id).firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: return null
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

    /** Caminho relativo à raiz de um documento escolhido pelo seletor de arquivos, ou null se estiver fora dela. */
    fun relativePathOf(documentUri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull() ?: return null
        return if (id.startsWith(idPrefix)) id.removePrefix(idPrefix) else null
    }
}
