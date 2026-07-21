package tachiyomi.data.release

import com.hippo.ehviewer.util.AppConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release from GitHub.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val info: String? = null,
    @SerialName("html_url") val releaseLink: String = "",
    @SerialName("assets") val assets: List<GitHubAssets> = emptyList(),
) {
    fun getDownloadLink(): String {
        val asset = assets.find { AppConfig.matchVariant(it.name) }
            ?: assets.find { it.name.endsWith(".apk", ignoreCase = true) }
            ?: assets.firstOrNull()
            ?: error("No APK assets in the latest GitHub release")
        // Prefer public CDN URL so download works without a GitHub token.
        return asset.browserDownloadUrl.ifBlank { asset.url }
    }
}

/**
 * Assets class containing download url.
 */
@Serializable
data class GitHubAssets(
    val url: String = "",
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)
