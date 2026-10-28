package dev.ignorantshuffle.companion

import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): AppVersion? {
            if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) return null
            val parts = value.split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class UpdateAsset(val name: String, val url: String, val size: Long, val sha256: String)
data class AndroidRelease(val tag: String, val version: String, val notes: String, val published: String,
    val preview: Boolean, val pageUrl: String, val asset: UpdateAsset?, val unavailable: String?)
data class AndroidReleasePage(val count: Int, val androidCount: Int, val newest: AndroidRelease?)

object AndroidReleases {
    const val REPOSITORY = "khivus/IgnorantShuffle"
    const val MAX_APK = 100_000_000L
    val releasesUrl = "https://github.com/$REPOSITORY/releases"
    fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun pageUrl(tag: String) = "https://github.com/$REPOSITORY/releases/tag/${encoded(tag)}"
    fun newer(candidate: AndroidRelease, old: AndroidRelease?) = old == null ||
        requireNotNull(AppVersion.parse(candidate.version)) > requireNotNull(AppVersion.parse(old.version))

    fun page(text: String, installed: String, previews: Boolean, debugBuild: Boolean): AndroidReleasePage {
        val json = JSONArray(text)
        require(json.length() <= 100) { "GitHub returned an invalid release page" }
        val current = requireNotNull(AppVersion.parse(installed)) { "Invalid installed app version" }
        var newest: AndroidRelease? = null
        var count = 0
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            if (item.optBoolean("draft")) continue
            val tag = item.optString("tag_name")
            if (!tag.startsWith("android/v")) continue
            val version = tag.removePrefix("android/v")
            val number = AppVersion.parse(version) ?: continue
            if (item.optBoolean("prerelease") && !previews) continue
            count++
            if (number <= current) continue
            val assets = item.optJSONArray("assets") ?: JSONArray()
            val names = if (debugBuild) listOf("IgnorantShuffle-Android-v$version-debug.apk", "IgnorantShuffle-Android-v$version.apk", "IgnorantShuffle-Android-v$version-release.apk")
                else listOf("IgnorantShuffle-Android-v$version.apk", "IgnorantShuffle-Android-v$version-release.apk")
            val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") in names }
            val selectedName = names.firstOrNull { name -> candidates.any { it.optString("name") == name } }
            val matching = candidates.filter { it.optString("name") == selectedName }
            var reason: String? = "This release has no compatible Android APK yet"
            var asset: UpdateAsset? = null
            if (matching.size > 1) reason = "The release contains ambiguous APK assets"
            if (matching.size == 1) {
                val apk = matching.single()
                val size = apk.optLong("size", -1)
                val url = apk.optString("browser_download_url")
                val digest = apk.optString("digest").removePrefix("sha256:")
                val allowed = listOf(tag, encoded(tag)).any { path ->
                    url == "https://github.com/$REPOSITORY/releases/download/$path/$selectedName"
                }
                reason = when {
                    apk.optString("state") != "uploaded" -> "The APK is still uploading"
                    size !in 1..MAX_APK -> "The APK size is invalid or exceeds 100 MB"
                    !allowed -> "The APK link does not match this repository and release"
                    !apk.optString("digest").startsWith("sha256:") || !digest.matches(Regex("[a-fA-F0-9]{64}")) -> "GitHub has not supplied a valid APK checksum yet"
                    else -> null
                }
                if (reason == null) asset = UpdateAsset(requireNotNull(selectedName), url, size, digest.lowercase())
            }
            val candidate = AndroidRelease(tag, version, item.optString("body").take(100_000), item.optString("published_at"),
                item.optBoolean("prerelease"), pageUrl(tag), asset, reason)
            if (newer(candidate, newest)) newest = candidate
        }
        return AndroidReleasePage(json.length(), count, newest)
    }
}

data class PackageDescription(val id: String, val version: String, val code: Long, val minSdk: Int, val signers: Set<String>)
object UpdatePackagePolicy {
    fun validate(archive: PackageDescription, installed: PackageDescription, release: AndroidRelease, sdk: Int) {
        require(archive.id == installed.id && archive.version == release.version && archive.code > installed.code) {
            "APK is for another app/version or cannot upgrade this installation"
        }
        require(archive.minSdk <= sdk) { "This update requires Android API ${archive.minSdk} or newer" }
        require(installed.signers.isNotEmpty() && archive.signers == installed.signers) {
            "APK signing key differs from this installation. Keep using APKs signed with the same key; your data was not changed"
        }
    }
}

object UpdateUrls {
    fun allowed(uri: URI): Boolean = uri.scheme == "https" && uri.port in listOf(-1, 443) &&
        uri.rawUserInfo == null && uri.fragment == null && uri.host in setOf("api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}
