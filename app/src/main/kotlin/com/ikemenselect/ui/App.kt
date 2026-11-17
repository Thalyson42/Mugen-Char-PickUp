package com.ikemenselect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikemenselect.Face
import com.ikemenselect.MainViewModel
import com.ikemenselect.core.isBlankCell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    vm: MainViewModel,
    onPickFolder: () -> Unit,
    onPickSelectDef: () -> Unit,
    onPickSystemDef: () -> Unit,
    onPickDef: (Int) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(s.message) {
        s.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Mugen Char PickUp") },
                actions = {
                    if (s.hasFolder) {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("Menu") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Trocar pasta do Ikemen GO") },
                                    onClick = { menuOpen = false; onPickFolder() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tamanho do grid (${s.rows}×${s.columns})") },
                                    onClick = { menuOpen = false; sizeDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Recarregar select.def") },
                                    onClick = { menuOpen = false; vm.reload() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Escolher select.def…") },
                                    onClick = { menuOpen = false; onPickSelectDef() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Escolher system.def…") },
                                    onClick = { menuOpen = false; onPickSystemDef() },
                                )
                                if (s.selectManual || s.systemManual) {
                                    DropdownMenuItem(
                                        text = { Text("Voltar à detecção automática") },
                                        onClick = { menuOpen = false; vm.useAutoDetection() },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!s.ready) {
                Welcome(
                    hasFolder = s.hasFolder,
                    loading = s.loading,
                    error = s.error,
                    onPickFolder = onPickFolder,
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        text = "select: ${s.selectPath}${if (s.selectManual) " (manual)" else ""}\n" +
                            "system: ${s.systemPath.ifEmpty { "não encontrado" }}${if (s.systemManual) " (manual)" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    s.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    SelectGrid(
                        cells = s.cells,
                        columns = s.columns,
                        selected = s.selected,
                        onSelect = vm::select,
                        onSwap = vm::swap,
                        peekFace = vm::peekFace,
                        loadFace = { vm.face(it) },
                        faceEpoch = s.portraitEpoch,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    BottomBar(
                        selected = s.selected,
                        label = s.selected?.let { s.cells.getOrNull(it) }?.label,
                        info = s.selected?.let { s.cells.getOrNull(it)?.entry }
                            ?.let { (vm.peekFace(it) as? Face.Image)?.info },
                        blank = s.selected?.let { s.cells.getOrNull(it).isBlankCell() } ?: true,
                        canUndo = s.canUndo,
                        loading = s.loading,
                        onUpdate = vm::updateCharList,
                        onUndo = vm::undo,
                        onAdd = { s.selected?.let(onPickDef) },
                        onRemove = { s.selected?.let(vm::remove) },
                    )
                }
                if (s.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    if (sizeDialog) {
        GridSizeDialog(
            rows = s.rows,
            columns = s.columns,
            onDismiss = { sizeDialog = false },
            onConfirm = { r, c -> vm.setGridSize(r, c); sizeDialog = false },
        )
    }
}

@Composable
private fun Welcome(hasFolder: Boolean, loading: Boolean, error: String?, onPickFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator()
            return@Column
        }
        Text(
            "Escolha a pasta do Ikemen GO (a que contém \"data\" e \"chars\").",
            style = MaterialTheme.typography.bodyLarge,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        Button(onClick = onPickFolder, modifier = Modifier.padding(top = 20.dp)) {
            Text(if (hasFolder) "Escolher outra pasta" else "Escolher pasta")
        }
    }
}

@Composable
private fun BottomBar(
    selected: Int?,
    label: String?,
    info: String?,
    blank: Boolean,
    canUndo: Boolean,
    loading: Boolean,
    onUpdate: () -> Unit,
    onUndo: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (blank) "Espaço ${selected + 1} (vazio)"
                        else "Espaço ${selected + 1}: $label" + (info?.let { "\nretrato: $it" } ?: ""),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (blank) {
                        Button(onClick = onAdd) { Text("Adicionar…") }
                    } else {
                        OutlinedButton(onClick = onRemove) { Text("Remover") }
                    }
                }
            } else {
                Text(
                    "Toque numa célula para selecionar. Segure e arraste para mover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUpdate, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Text("Update char list")
                }
                OutlinedButton(onClick = onUndo, enabled = canUndo) { Text("Desfazer") }
            }
        }
    }
}

@Composable
private fun GridSizeDialog(rows: Int, columns: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var r by remember { mutableStateOf(rows.toString()) }
    var c by remember { mutableStateOf(columns.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tamanho do grid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Grava rows e columns no system.def do jogo (cria um system.def.bak). " +
                        "Se o screenpack não tiver espaço para o novo tamanho, ajuste cell.size/pos nele.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(r, { r = it.filter(Char::isDigit) }, label = { Text("Linhas (rows)") }, singleLine = true)
                OutlinedTextField(c, { c = it.filter(Char::isDigit) }, label = { Text("Colunas (columns)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rr = r.toIntOrNull()
                val cc = c.toIntOrNull()
                if (rr != null && cc != null && rr in 1..100 && cc in 1..30) onConfirm(rr, cc)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
