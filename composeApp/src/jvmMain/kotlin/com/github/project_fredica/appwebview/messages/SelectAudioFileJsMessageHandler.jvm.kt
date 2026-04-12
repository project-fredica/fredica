package com.github.project_fredica.appwebview.messages

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual suspend fun selectAudioFile(): String? = withContext(Dispatchers.IO) {
    val chooser = JFileChooser().apply {
        dialogTitle = "选择音频文件"
        fileFilter = FileNameExtensionFilter(
            "音频文件 (wav, mp3, flac, m4a, ogg, wma, aac)",
            "wav", "mp3", "flac", "m4a", "ogg", "wma", "aac"
        )
        isAcceptAllFileFilterUsed = true
    }
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}
