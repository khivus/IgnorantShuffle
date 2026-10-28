package dev.ignorantshuffle.companion

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class UpdateFileProvider : FileProvider(R.xml.update_paths)
enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, CURRENT, NO_RELEASE, ERROR }
object UpdateSchedule {
    fun due(enabled: Boolean, last: Long, now: Long): Boolean = enabled && (last <= 0 || now < last || now - last >= 86_400_000L)
    fun prompt(tag: String, skipped: String?, manual: Boolean): Boolean = manual || tag != skipped
}

/** Application-scoped so progress and a verified download survive Activity recreation. */
class AppUpdates(private val context: Context, private val event: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val http = UpdateHttp()
    private val prefs = context.getSharedPreferences("android-updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "updates")
    private val saved = AtomicFile(File(directory, "ready.json"))
    private var cancellation = AtomicBoolean(false)
    private var generation = 0L
    private var restoring = true
    private var foregroundPending = false
    private var manualPending = false
    var phase by mutableStateOf(UpdatePhase.VERIFYING); private set
    var release by mutableStateOf<AndroidRelease?>(null); private set
    var progress by mutableStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf("Loading saved update state"); private set
    var downloaded by mutableStateOf<File?>(null); private set
    var installRequest by mutableStateOf<File?>(null); private set
    var visible by mutableStateOf(false); private set
    var automatic by mutableStateOf(prefs.getBoolean("automatic", true)); private set
    var previews by mutableStateOf(prefs.getBoolean("previews", BuildConfig.DEBUG)); private set
    val busy get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)

    init {
        worker.execute {
            var restored: Pair<AndroidRelease, File>? = null
            try {
                if (saved.baseFile.exists() || File(saved.baseFile.path + ".bak").exists()) {
                    val json = JSONObject(saved.openRead().bufferedReader().use { it.readText() })
                    val page = AndroidReleases.page("[${json.getJSONObject("release")}]",
                        BuildConfig.VERSION_NAME, previews = previews, debugBuild = BuildConfig.DEBUG)
                    val update = requireNotNull(page.newest) { "Saved update is no longer newer" }
                    val filename = json.getString("file")
                    require(filename.matches(Regex("[a-f0-9-]+\\.apk"))) { "Invalid cached update filename" }
                    val file = File(directory, filename)
                    validateFile(file, update, AtomicBoolean(false))
                    restored = update to file
                }
            } catch (e: Exception) {
                saved.delete(); eventOnMain("Discarded stale/invalid cached update: ${e.message}")
            }
            val result = restored
            directory.listFiles()?.filter { it.extension in setOf("part", "apk") && it != result?.second }?.forEach { it.delete() }
            handler.post {
                restoring = false
                if (result != null) {
                    release = result.first; downloaded = result.second; phase = UpdatePhase.READY
                    notice = "Verified update is ready to install"
                } else { phase = UpdatePhase.IDLE; notice = "Check for a newer Android version" }
                when { manualPending -> check(true); foregroundPending && downloaded == null -> check(false) }
                manualPending = false; foregroundPending = false
            }
        }
    }
    private fun eventOnMain(text: String) { handler.post { event("UPDATE $text") } }
    fun onForeground() { if (restoring) foregroundPending = true else check(false) }
    fun open() { visible = true }
    fun dismiss(skip: Boolean = false) {
        if (skip) release?.let { prefs.edit().putString("skipped", it.tag).apply() }
        visible = false
    }
    fun preferences(auto: Boolean = automatic, preview: Boolean = previews) {
        if (busy) return
        automatic = auto; previews = preview
        prefs.edit().putBoolean("automatic", auto).putBoolean("previews", preview).apply()
        if (release?.preview == true && !preview) {
            release = null; downloaded = null; saved.delete(); phase = UpdatePhase.IDLE
        }
        notice = "Update preferences saved"; event("UPDATE preferences automatic=$auto previews=$preview")
    }
    fun check(manual: Boolean = true) {
        if (manual) visible = true
        if (restoring) { manualPending = manualPending || manual; return }
        if (busy || (!manual && downloaded != null)) return
        if (!manual && !UpdateSchedule.due(automatic, prefs.getLong("lastAttempt", 0), System.currentTimeMillis())) return
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
        val token = ++generation; val canceled = AtomicBoolean(false); cancellation = canceled
        val allowPreviews = previews
        phase = UpdatePhase.CHECKING; error = null; notice = "Checking Android releases…"
        event("UPDATE checking; manual=$manual previews=$allowPreviews")
        worker.execute {
            try {
                var next: AndroidRelease? = null; var androidCount = 0
                for (page in 1..10) {
                    val (status, body) = http.text("https://api.github.com/repos/${AndroidReleases.REPOSITORY}/releases?per_page=100&page=$page", canceled)
                    if (status == 404 && page == 1) break
                    check(status == 200) { httpError(status) }
                    val entries = AndroidReleases.page(body, BuildConfig.VERSION_NAME, allowPreviews, BuildConfig.DEBUG)
                    androidCount += entries.androidCount
                    entries.newest?.let { if (AndroidReleases.newer(it, next)) next = it }
                    if (entries.count < 100) break
                    check(page < 10) { "Release scan reached 1000 entries and could not finish" }
                }
                val found = next
                handler.post {
                    if (token != generation || canceled.get()) return@post
                    if (found?.tag != release?.tag || found?.asset != release?.asset) { downloaded = null; saved.delete() }
                    release = found
                    phase = when { downloaded != null -> UpdatePhase.READY; found != null -> UpdatePhase.AVAILABLE
                        androidCount == 0 -> UpdatePhase.NO_RELEASE; else -> UpdatePhase.CURRENT }
                    notice = when (phase) {
                        UpdatePhase.NO_RELEASE -> "No Android release is published for this channel yet"
                        UpdatePhase.CURRENT -> "You have the latest Android version for this channel"
                        UpdatePhase.READY -> "Verified update is ready to install"
                        else -> "Android ${found?.version} is available"
                    }
                    if (found != null && UpdateSchedule.prompt(found.tag, prefs.getString("skipped", null), manual)) visible = true
                    event("UPDATE check result: $notice; tag=${found?.tag}; installable=${found?.asset != null}")
                }
            } catch (e: Exception) { failure(token, canceled, e) }
        }
    }
    private fun httpError(status: Int) = if (status == 403 || status == 429) "GitHub rate limit or access restriction. Try again later"
        else "GitHub request failed (HTTP $status)"
    fun cancel() {
        manualPending = false; foregroundPending = false
        cancellation.set(true); ++generation; http.cancel()
        phase = if (downloaded != null) UpdatePhase.READY else if (release != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE
        notice = "Update operation canceled"; error = null; event("UPDATE canceled")
    }
    private fun failure(token: Long, canceled: AtomicBoolean, exception: Exception) { handler.post {
        if (token != generation || canceled.get()) return@post
        phase = if (downloaded != null) UpdatePhase.READY else UpdatePhase.ERROR
        error = exception.message ?: exception.toString(); notice = "Update operation failed; you can retry"
        event("UPDATE ERROR: $exception")
    } }
    fun download() {
        if (busy) return
        val update = release ?: return
        val asset = update.asset ?: return
        val token = ++generation; val canceled = AtomicBoolean(false); cancellation = canceled
        phase = UpdatePhase.DOWNLOADING; progress = 0; error = null; notice = "Downloading update…"
        event("UPDATE download requested: ${update.tag} ${asset.name} bytes=${asset.size}")
        worker.execute {
            directory.mkdirs()
            val id = UUID.randomUUID().toString()
            val partial = File(directory, "$id.part"); val destination = File(directory, "$id.apk")
            try {
                http.get(asset.url, canceled) { status, input ->
                    check(status == 200) { httpError(status) }
                    partial.outputStream().use { output -> VerifiedUpdateDownload.copy(input, output, asset, canceled) { percent ->
                        handler.post { if (token == generation) progress = percent }
                    } }
                }
                handler.post { if (token == generation) { phase = UpdatePhase.VERIFYING; notice = "Verifying APK…" } }
                validatePackage(partial, update)
                if (canceled.get()) throw CancellationException("Canceled")
                check(partial.renameTo(destination)) { "Could not save the downloaded APK" }
                saveReady(update, destination)
                if (canceled.get()) { saved.delete(); throw CancellationException("Canceled") }
                handler.post {
                    if (token != generation || canceled.get()) { destination.delete(); return@post }
                    downloaded = destination; phase = UpdatePhase.READY; notice = "Verified update is ready to install"
                    event("UPDATE verified: ${update.tag}; ready for user installation")
                }
            } catch (e: Exception) { destination.delete(); failure(token, canceled, e) }
            finally { partial.delete() }
        }
    }
    private fun saveReady(update: AndroidRelease, file: File) {
        val asset = requireNotNull(update.asset)
        val jsonRelease = JSONObject().put("tag_name", update.tag).put("body", update.notes).put("published_at", update.published)
            .put("prerelease", update.preview).put("assets", org.json.JSONArray().put(JSONObject().put("name", asset.name)
                .put("state", "uploaded").put("size", asset.size).put("digest", "sha256:${asset.sha256}").put("browser_download_url", asset.url)))
        val stream = saved.startWrite()
        try {
            stream.write(JSONObject().put("release", jsonRelease).put("file", file.name).toString().toByteArray(Charsets.UTF_8))
            saved.finishWrite(stream)
        } catch (e: Exception) { saved.failWrite(stream); throw e }
    }
    private fun validateFile(file: File, update: AndroidRelease, canceled: AtomicBoolean) {
        val asset = requireNotNull(update.asset) { update.unavailable.orEmpty() }
        check(file.isFile && file.length() == asset.size) { "Saved APK is missing or changed; download it again" }
        file.inputStream().use { VerifiedUpdateDownload.copy(it, object : OutputStream() {
            override fun write(value: Int) { }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { }
        }, asset, canceled) { } }
        validatePackage(file, update)
    }
    @Suppress("DEPRECATION")
    private fun validatePackage(file: File, update: AndroidRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        fun describe(info: PackageInfo): PackageDescription {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return PackageDescription(info.packageName, info.versionName.orEmpty(), if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, signatures.orEmpty().map { it.toCharsString() }.toSet())
        }
        val installed = describe(pm.getPackageInfo(context.packageName, flags))
        val archive = describe(requireNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags)) { "Downloaded file is not a readable Android APK" })
        UpdatePackagePolicy.validate(archive, installed, update, Build.VERSION.SDK_INT)
        ZipFile(file).use { zip -> check(Build.SUPPORTED_ABIS.any { zip.getEntry("lib/$it/libignorant_shuffle.so") != null }) {
            "APK does not include a native shuffle library compatible with this phone"
        } }
    }
    fun prepareInstall() {
        if (busy || installRequest != null) return
        val update = release ?: return
        val file = downloaded ?: return
        val token = ++generation; val canceled = AtomicBoolean(false); cancellation = canceled
        phase = UpdatePhase.VERIFYING; error = null; notice = "Rechecking saved APK…"
        worker.execute {
            try {
                validateFile(file, update, canceled)
                handler.post { if (token == generation && !canceled.get()) { phase = UpdatePhase.READY; installRequest = file } }
            } catch (e: Exception) {
                handler.post { if (token == generation) { downloaded = null; saved.delete() } }
                failure(token, canceled, e)
            }
        }
    }
    fun takeInstallRequest(): File? = installRequest.also { installRequest = null }
    fun installerOpened() {
        notice = "Installer opened. Complete Android's prompts; you can retry here if canceled"
        event("UPDATE installer opened; installation completion is not yet confirmed")
    }
    fun installProblem(message: String) { error = message; event("UPDATE INSTALL: $message") }
    fun snapshot() = "Updates: $phase; daily=$automatic previews=$previews tag=${release?.tag} progress=$progress ready=${downloaded != null}; error=$error"
}
