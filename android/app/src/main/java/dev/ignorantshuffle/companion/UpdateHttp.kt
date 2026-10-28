package dev.ignorantshuffle.companion

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, cancelable HTTPS requests. No network calls run on the playback thread. */
class UpdateHttp(private val allowed: (URI) -> Boolean = UpdateUrls::allowed, private val timeout: Int = 20_000) {
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { connection?.disconnect() }
    fun <T> get(url: String, canceled: AtomicBoolean, block: (Int, InputStream) -> T): T {
        var next = URI(url)
        val started = System.nanoTime()
        try {
            for (redirect in 0..5) {
                if (canceled.get()) throw CancellationException("Canceled")
                require(allowed(next)) { "Update URL/redirect is not an allowed GitHub HTTPS address" }
                val request = next.toURL().openConnection() as HttpURLConnection
                connection = request
                request.instanceFollowRedirects = false
                request.connectTimeout = timeout; request.readTimeout = timeout
                request.setRequestProperty("Accept", "application/vnd.github+json")
                request.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                request.setRequestProperty("User-Agent", "IgnorantShuffle-Android/${BuildConfig.VERSION_NAME}")
                if (canceled.get()) throw CancellationException("Canceled")
                val status = request.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    next = next.resolve(requireNotNull(request.getHeaderField("Location")) { "Update redirect has no destination" })
                    request.disconnect(); continue
                }
                val source = if (status in 200..299) request.inputStream else request.errorStream ?: java.io.ByteArrayInputStream(byteArrayOf())
                return source.use { stream -> block(status, object : InputStream() {
                    private fun active() {
                        if (canceled.get()) throw CancellationException("Canceled")
                        check(System.nanoTime() - started < 300_000_000_000L) { "Update request timed out" }
                    }
                    override fun read(): Int { active(); return stream.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { active(); return stream.read(buffer, offset, length) }
                }) }
            }
            error("Too many update redirects")
        } finally { connection?.disconnect(); connection = null }
    }
    fun text(url: String, canceled: AtomicBoolean): Pair<Int, String> = get(url, canceled) { status, input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(bytes.size() + n <= 2_000_000) { "GitHub release response exceeds 2 MB" }
            bytes.write(buffer, 0, n)
        }
        status to bytes.toString("UTF-8")
    }
}

object VerifiedUpdateDownload {
    fun copy(input: InputStream, output: OutputStream, asset: UpdateAsset, canceled: AtomicBoolean, progress: (Int) -> Unit) {
        require(asset.size in 1..AndroidReleases.MAX_APK)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L; var last = -1
        while (true) {
            if (canceled.get()) throw CancellationException("Canceled")
            val n = input.read(buffer); if (n < 0) break
            total += n
            require(total <= asset.size && total <= AndroidReleases.MAX_APK) { "Downloaded APK is larger than expected" }
            output.write(buffer, 0, n); digest.update(buffer, 0, n)
            val percent = (total * 100 / asset.size).toInt()
            if (percent != last) { last = percent; progress(percent) }
        }
        require(total == asset.size) { "APK download is incomplete; retry the download" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == asset.sha256) { "APK checksum mismatch; download was discarded" }
    }
}
