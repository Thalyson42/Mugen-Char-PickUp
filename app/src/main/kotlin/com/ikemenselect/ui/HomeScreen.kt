package com.ikemenselect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Primeira tela depois de escolher a pasta: seleção de personagens ou visualizador. */
@Composable
fun HomeScreen(folderName: String, onSelection: () -> Unit, onView: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Pasta: $folderName",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Button(onClick = onSelection, modifier = Modifier.fillMaxWidth()) {
            Text("Character Selection")
        }
        Text(
            "Organize o select.def: ordem, remoção e adição de personagens.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onView, modifier = Modifier.fillMaxWidth()) {
            Text("Character View")
        }
        Text(
            "Veja dois personagens num cenário e as animações de cada um.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
