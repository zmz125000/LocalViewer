package com.hippo.ehviewer.updater

import com.ehviewer.core.files.write
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.io.asSource
import kotlinx.serialization.json.Json
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import tachiyomi.data.release.GithubArtifacts
import tachiyomi.data.release.GithubCommitComparison
import tachiyomi.data.release.GithubRelease
import tachiyomi.data.release.GithubRepo
import tachiyomi.data.release.GithubWorkflowRuns

private const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
private const val LATEST_RELEASE_URL = "$API_URL/releases/latest"

/** GitHub returns many unused fields; shared ktorClient has no ContentNegotiation. */
private val GithubJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

object AppUpdater {
    suspend fun checkForUpdate(forceCheck: Boolean = false): Release? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        if (forceCheck || interval != 0 && now > last + interval.days) {
            Settings.lastUpdateTime = now.epochSeconds
            if (Settings.useCIUpdateChannel.value) {
                val curSha = BuildConfig.COMMIT_SHA
                val branch = ghGet<GithubRepo>(API_URL).defaultBranch
                val workflowRunsUrl =
                    "$API_URL/actions/workflows/ci.yml/runs?branch=$branch&event=push&status=success&per_page=1"
                val workflowRun = ghGet<GithubWorkflowRuns>(workflowRunsUrl).workflowRuns.firstOrNull()
                    ?: return null
                val shortSha = workflowRun.headSha.take(7)
                if (shortSha != curSha) {
                    val artifacts = ghGet<GithubArtifacts>(workflowRun.artifactsUrl)
                    val archiveUrl = artifacts.getDownloadLink()
                    val changelog = runSuspendCatching {
                        val commitComparisonUrl = "$API_URL/compare/$curSha...$shortSha"
                        val result = ghGet<GithubCommitComparison>(commitComparisonUrl)
                        result.commits.joinToString("\n") { commit ->
                            "${commit.commit.message.takeWhile { it != '\n' }} (@${commit.commit.author.name})"
                        }
                    }.getOrDefault(workflowRun.title)
                    return Release(shortSha, changelog, archiveUrl)
                }
            } else {
                val curVersion = normalizeVersion(BuildConfig.RAW_VERSION_NAME)
                val release = ghGet<GithubRelease>(LATEST_RELEASE_URL)
                val latestVersion = normalizeVersion(release.version)
                val description = release.info.orEmpty().ifBlank { release.version }
                val downloadUrl = release.getDownloadLink()
                if (latestVersion != curVersion) {
                    return Release(release.version, description, downloadUrl)
                }
            }
        }
        return null
    }

    suspend fun downloadUpdate(url: String, path: Path) {
        val isZip = url.contains("actions/artifacts") || url.endsWith(".zip") || url.endsWith("/zip")
        // Public release assets use browser_download_url (no API Accept dance).
        // CI artifacts still need the Actions archive API URL.
        ghStatement(url) {
            if (isZip || url.contains("api.github.com")) {
                accept(ContentType.Application.OctetStream)
            }
        }.execute { response ->
            if (isZip) {
                response.bodyAsChannel().toInputStream().use { stream ->
                    ZipInputStream(stream).use { zip ->
                        zip.nextEntry
                        path.write { transferFrom(zip.asSource()) }
                    }
                }
            } else {
                response.bodyAsChannel().copyTo(path)
            }
        }
    }
}

private fun normalizeVersion(version: String) = version.trim().removePrefix("v").removePrefix("V")

private suspend inline fun <reified T> ghGet(url: String): T {
    val text = ghStatement(url).execute { it.bodyAsText() }
    return GithubJson.decodeFromString(text)
}

private suspend inline fun ghStatement(
    url: String,
    builder: HttpRequestBuilder.() -> Unit = {},
) = ktorClient.prepareGet(url) {
    // Override Chrome Accept from configureCommon — GitHub API needs JSON.
    header(HttpHeaders.Accept, "application/vnd.github+json")
    header("X-GitHub-Api-Version", "2022-11-28")
    // Public repo reads work without auth. Avoid sending a foreign/dead PAT (401).
    apply(builder)
}

data class Release(
    val version: String,
    val changelog: String,
    val downloadLink: String,
)
