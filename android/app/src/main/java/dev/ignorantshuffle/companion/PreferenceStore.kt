package dev.ignorantshuffle.companion

import android.content.Context
import android.util.AtomicFile
import java.io.File

class PreferenceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "app-preferences.json"))
    fun load(): AppPreferences {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AppPreferences()
        return PreferenceJson.decode(file.openRead().bufferedReader().use { it.readText() })
    }
    fun save(value: AppPreferences) {
        val bytes = PreferenceJson.encode(value).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
