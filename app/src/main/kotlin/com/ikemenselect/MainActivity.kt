package com.ikemenselect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.ikemenselect.ui.App
import com.ikemenselect.ui.IkemenTheme

/** Seletor de arquivo que já abre dentro da pasta chars do Ikemen GO. */
class OpenDefContract : ActivityResultContracts.OpenDocument() {
    var initial: Uri? = null

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).also { intent ->
            initial?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val defContract = OpenDefContract()
    private var pendingIndex = -1

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.onTreePicked(uri)
        }
    }

    private val defLauncher = registerForActivityResult(defContract) { uri ->
        if (uri != null && pendingIndex >= 0) vm.addManual(uri, pendingIndex)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IkemenTheme {
                App(
                    vm = vm,
                    onPickFolder = { folderLauncher.launch(null) },
                    onPickDef = { index ->
                        pendingIndex = index
                        defContract.initial = vm.charsFolderUri()
                        defLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }
}
