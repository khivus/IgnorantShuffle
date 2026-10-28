package dev.ignorantshuffle.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AndroidUpdatesTest {
    private val bytes = "signed-apk-fixture-bytes".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun release(tag: String = "android/v0.5.0", preview: Boolean = false, debug: Boolean = true): JSONObject {
        val version = tag.substringAfterLast('v')
        val name = "IgnorantShuffle-Android-v$version${if (debug) "-debug" else ""}.apk"
        return JSONObject().put("tag_name", tag).put("prerelease", preview).put("body", "## Changes\n- Better updates")
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", bytes.size).put("state", "uploaded")
                .put("digest", "sha256:$digest").put("browser_download_url",
                    "https://github.com/${AndroidReleases.REPOSITORY}/releases/download/${AndroidReleases.encoded(tag)}/$name")))
    }
    private fun page(vararg releases: JSONObject, previews: Boolean = false, debug: Boolean = true) =
        AndroidReleases.page(JSONArray(releases.toList()).toString(), "0.4.0", previews, debug)
    private fun rejects(block: () -> Unit) {
        var rejected = false; try { block() } catch (_: Exception) { rejected = true }; assertTrue(rejected)
    }
    @Test fun versionsAreNumericAndStrict() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        listOf("v1.2.3", "1.2", "1.2.3.4", "01.2.3", "1.2.3-beta", "999999999999.0.0").forEach { assertNull(AppVersion.parse(it)) }
    }
    @Test fun onlyAndroidTagsCanBeOfferedRegardlessOfLatestReleaseOrder() {
        val result = page(release("windows/v99.0.0"), release("core/v98.0.0"), release("v97.0.0"), release())
        assertEquals(4, result.count); assertEquals(1, result.androidCount)
        assertEquals("android/v0.5.0", result.newest!!.tag)
    }
    @Test fun highestVersionWinsWithoutDependingOnPublishOrder() {
        assertEquals("0.10.0", page(release("android/v0.9.0"), release("android/v0.10.0"), release()).newest!!.version)
    }
    @Test fun windowsOnlyRepositoryIsNotCalledAnUpToDateAndroidRelease() {
        val result = page(release("windows/v99.0.0"))
        assertEquals(0, result.androidCount); assertNull(result.newest)
    }
    @Test fun equalAndOlderVersionsNeverDowngrade() {
        assertNull(page(release("android/v0.4.0"), release("android/v0.3.0")).newest)
    }
    @Test fun previewsAreOptionalAndDraftsAlwaysIgnored() {
        assertNull(page(release(preview = true)).newest)
        assertNotNull(page(release(preview = true), previews = true).newest)
        assertNull(page(release().put("draft", true)).newest)
    }
    @Test fun compatibleAssetRequiresExactReleaseUrlAndChecksum() {
        val result = page(release()).newest!!
        assertNotNull(result.asset); assertEquals(digest, result.asset!!.sha256); assertNull(result.unavailable)
    }
    @Test fun missingAssetIsVisibleWithInstallationDisabled() {
        val result = page(release().put("assets", JSONArray())).newest!!
        assertNull(result.asset); assertNotNull(result.unavailable)
    }
    @Test fun malformedOrWrongRepositoryLinksCannotDownload() {
        for (url in listOf("http://github.com/evil.apk", "https://evil.test/evil.apk", "https://github.com/else/repo/releases/download/android%2Fv0.5.0/a.apk")) {
            val json = release(); json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertNull(page(json).newest!!.asset)
        }
    }
    @Test fun missingDigestOrOversizedAndAmbiguousAssetsAreBlocked() {
        val missing = release(); missing.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(page(missing).newest!!.asset)
        val huge = release(); huge.getJSONArray("assets").getJSONObject(0).put("size", AndroidReleases.MAX_APK + 1)
        assertNull(page(huge).newest!!.asset)
        val duplicate = release(); val assets = duplicate.getJSONArray("assets"); assets.put(JSONObject(assets.getJSONObject(0).toString()))
        assertNull(page(duplicate).newest!!.asset)
    }
    @Test fun releaseBuildDoesNotOfferDebugApks() {
        assertNull(page(release(), debug = false).newest!!.asset)
        assertNotNull(page(release(debug = false), debug = false).newest!!.asset)
    }
    @Test fun moreThanOneApiPageMustNotBeParsedAsASinglePage() {
        val entries = JSONArray(); repeat(101) { entries.put(release()) }
        rejects { AndroidReleases.page(entries.toString(), "0.4.0", false, true) }
    }
    @Test fun skippedVersionCanStillBeCheckedManually() {
        assertFalse(UpdateSchedule.prompt("android/v0.5.0", "android/v0.5.0", false))
        assertTrue(UpdateSchedule.prompt("android/v0.5.0", "android/v0.5.0", true))
        assertTrue(UpdateSchedule.prompt("android/v0.6.0", "android/v0.5.0", false))
    }
    @Test fun automaticChecksRespectDailyLimitOptOutAndClockChanges() {
        assertFalse(UpdateSchedule.due(false, 0, 1000))
        assertTrue(UpdateSchedule.due(true, 0, 1000))
        assertFalse(UpdateSchedule.due(true, 1000, 2000))
        assertTrue(UpdateSchedule.due(true, 1000, 86_401_000))
        assertTrue(UpdateSchedule.due(true, 2000, 1000))
    }
    @Test fun packageMustUpgradeSameAppWithSameKeyAndSupportedAndroidVersion() {
        val update = page(release()).newest!!
        val installed = PackageDescription("dev.ignorantshuffle.android", "0.4.0", 5, 26, setOf("stable-key"))
        val archive = installed.copy(version = "0.5.0", code = 6)
        UpdatePackagePolicy.validate(archive, installed, update, 26)
        listOf(archive.copy(id = "another.app"), archive.copy(version = "0.4.0"), archive.copy(code = 5),
            archive.copy(signers = emptySet()), archive.copy(signers = setOf("ci-temporary-key")), archive.copy(minSdk = 27)).forEach {
            rejects { UpdatePackagePolicy.validate(it, installed, update, 26) }
        }
    }
    @Test fun downloadedBytesMustMatchBothLengthAndDigest() {
        val asset = page(release()).newest!!.asset!!
        val output = ByteArrayOutputStream(); val progress = mutableListOf<Int>()
        VerifiedUpdateDownload.copy(ByteArrayInputStream(bytes), output, asset, AtomicBoolean(false), progress::add)
        assertArrayEquals(bytes, output.toByteArray()); assertEquals(100, progress.last())
        rejects { VerifiedUpdateDownload.copy(ByteArrayInputStream(bytes.dropLast(1).toByteArray()), ByteArrayOutputStream(), asset, AtomicBoolean(false)) { } }
        rejects { VerifiedUpdateDownload.copy(ByteArrayInputStream(bytes + byteArrayOf(1)), ByteArrayOutputStream(), asset, AtomicBoolean(false)) { } }
        rejects { VerifiedUpdateDownload.copy(ByteArrayInputStream(bytes.reversedArray()), ByteArrayOutputStream(), asset, AtomicBoolean(false)) { } }
    }
    @Test fun cancellationStopsCopyBeforeWritingAnyBytes() {
        val output = ByteArrayOutputStream()
        try {
            VerifiedUpdateDownload.copy(ByteArrayInputStream(bytes), output, page(release()).newest!!.asset!!, AtomicBoolean(true)) { }
            fail("Cancellation must throw")
        } catch (_: CancellationException) { assertEquals(0, output.size()) }
    }
    @Test fun redirectsRequireGithubHttpsWithoutCredentialsOrUnexpectedPorts() {
        assertTrue(UpdateUrls.allowed(URI("https://release-assets.githubusercontent.com/file?token=public-download")))
        listOf("http://github.com/a", "https://github.com.evil.test/a", "https://user@github.com/a", "https://github.com:444/a", "https://evil.test/a").forEach {
            assertFalse(UpdateUrls.allowed(URI(it)))
        }
    }
    @Test fun httpStreamsRealLocalhostResponseAndRejectsRedirectBeforeFollowing() {
        fun request(redirect: Boolean) {
            val server = ServerSocket(0); server.soTimeout = 5000
            val serverThread = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().use { out ->
                        if (redirect) out.write("HTTP/1.1 302 Found\r\nLocation: http://untrusted.invalid/file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        else { out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray()) }
                    }
                }
            }
            try {
                val http = UpdateHttp({ it.scheme == "http" && it.host == "127.0.0.1" && it.port == server.localPort }, 2000)
                val call = { http.text("http://127.0.0.1:${server.localPort}/releases", AtomicBoolean(false)) }
                if (redirect) rejects { call() } else assertEquals(200 to "[]", call())
            } finally { server.close(); serverThread.join(5000); assertFalse(serverThread.isAlive) }
        }
        request(false); request(true)
    }
}
