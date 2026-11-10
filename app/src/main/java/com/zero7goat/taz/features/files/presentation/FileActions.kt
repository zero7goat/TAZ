package com.zero7goat.taz.features.files.presentation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.zero7goat.taz.features.files.data.ExternalFile
import com.zero7goat.taz.features.files.data.FileEntity
import java.util.concurrent.CancellationException

enum class ExternalAction { SHARE, OPEN }

/**
 * Shares the file with, or opens it in, another app. The other app only ever receives a
 * content:// URI for a temporary copy (see ShareStaging); no private path is exposed.
 */
suspend fun launchExternal(
    context: Context,
    viewModel: FilesViewModel,
    file: FileEntity,
    action: ExternalAction,
    onMessage: (String) -> Unit
) {
    when (val prepared = viewModel.prepareExternal(file)) {
        ExternalFile.Missing -> onMessage("The stored copy of this file is missing")
        ExternalFile.Failed -> onMessage("Couldn't prepare the file. Check free storage.")
        is ExternalFile.Ready -> {
            val intent = when (action) {
                ExternalAction.SHARE -> Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = prepared.mimeType
                        putExtra(Intent.EXTRA_STREAM, prepared.uri)
                        clipData = ClipData.newRawUri(prepared.displayName, prepared.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    null
                )
                ExternalAction.OPEN -> Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(prepared.uri, prepared.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                context.startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(
                    if (action == ExternalAction.SHARE) "No app is available to share with"
                    else "No app can open this type of file"
                )
            }
        }
    }
}
