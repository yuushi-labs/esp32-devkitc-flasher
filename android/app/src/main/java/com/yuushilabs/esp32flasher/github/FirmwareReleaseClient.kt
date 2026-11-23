package com.yuushilabs.esp32flasher.github

import com.yuushilabs.esp32flasher.core.FirmwareRelease
import com.yuushilabs.esp32flasher.core.FlashManifest
import com.yuushilabs.esp32flasher.core.FlashManifestParser
import com.yuushilabs.esp32flasher.core.ReleaseAsset
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class RepoRef(val owner: String, val repo: String) {
    val slug: String get() = "$owner/$repo"

    override fun toString(): String = slug

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

        fun parse(input: String): RepoRef? {
            val trimmed = input.trim().removePrefix("https://github.com/").removeSuffix("/")
            val normalized = trimmed.removeSuffix(".git")
            if (!PATTERN.matches(normalized)) return null
            val parts = normalized.split('/')
            return RepoRef(parts[0], parts[1])
        }
    }
}

data class CachedResponse(val etag: String?, val body: String)

interface ReleaseCache {
    fun read(key: String): CachedResponse?

    fun write(key: String, value: CachedResponse)
}

class MemoryReleaseCache : ReleaseCache {
    private val entries = LinkedHashMap<String, CachedResponse>()

    override fun read(key: String): CachedResponse? = entries[key]

    override fun write(key: String, value: CachedResponse) {
        entries[key] = value
    }
}

data class ReleaseListResult(
    val releases: List<FirmwareRelease>,
    val notModified: Boolean,
    val fromCache: Boolean,
)

class GitHubApiException(val statusCode: Int, message: String) : IOException(message)

class ChecksumMismatchException(message: String) : IOException(message)

/**
 * Reads GitHub Releases for the configured firmware repository.
 *
 * Responses are cached together with their `ETag`, so periodic refreshes answer `304 Not
 * Modified` and never burn rate limit. Downloads stream to memory with progress and are verified
 * against the release asset digest when GitHub provides one.
 */
class FirmwareReleaseClient(
    private val httpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_API_BASE,
    private val cache: ReleaseCache = MemoryReleaseCache(),
    private val tokenProvider: () -> String? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun listReleases(
        repository: RepoRef,
        limit: Int = 20,
        forceRefresh: Boolean = false,
    ): ReleaseListResult = withContext(ioDispatcher) {
        val url = "$baseUrl/repos/${repository.slug}/releases?per_page=$limit"
        val key = "releases:${repository.slug}:$limit"
        val cached = if (forceRefresh) null else cache.read(key)
        val request = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT_HEADER)
            .header("X-GitHub-Api-Version", API_VERSION)
            .apply {
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                cached?.etag?.let { header("If-None-Match", it) }
            }
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            when {
                response.code == 304 && cached != null -> ReleaseListResult(
                    releases = FlashManifestParser.parseReleases(cached.body),
                    notModified = true,
                    fromCache = true,
                )

                response.isSuccessful -> {
                    val body = response.body?.string().orEmpty()
                    cache.write(key, CachedResponse(response.header("ETag"), body))
                    ReleaseListResult(
                        releases = FlashManifestParser.parseReleases(body),
                        notModified = false,
                        fromCache = false,
                    )
                }

                else -> throw apiError(response.code, response.body?.string())
            }
        }
    }

    suspend fun latestRelease(repository: RepoRef, forceRefresh: Boolean = false): FirmwareRelease? =
        withContext(ioDispatcher) {
            val url = "$baseUrl/repos/${repository.slug}/releases/latest"
            val key = "latest:${repository.slug}"
            val cached = if (forceRefresh) null else cache.read(key)
            val request = Request.Builder()
                .url(url)
                .header("Accept", ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", API_VERSION)
                .apply {
                    tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                    cached?.etag?.let { header("If-None-Match", it) }
                }
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 304 && cached != null ->
                        FlashManifestParser.parseRelease(cached.body)

                    response.code == 404 -> null

                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        cache.write(key, CachedResponse(response.header("ETag"), body))
                        FlashManifestParser.parseRelease(body)
                    }

                    else -> throw apiError(response.code, response.body?.string())
                }
            }
        }

    suspend fun fetchManifest(asset: ReleaseAsset): FlashManifest =
        FlashManifestParser.parseManifest(String(downloadBytes(asset.downloadUrl, null), Charsets.UTF_8))

    suspend fun downloadAsset(
        asset: ReleaseAsset,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ByteArray {
        val bytes = downloadBytes(asset.downloadUrl, onProgress)
        if (asset.sizeBytes > 0 && bytes.size.toLong() != asset.sizeBytes) {
            throw ChecksumMismatchException(
                "ダウンロードしたサイズが一致しません (期待 ${asset.sizeBytes} / 実際 ${bytes.size})",
            )
        }
        val expected = asset.sha256
        if (expected != null && !FlashManifestParser.verifySha256(bytes, expected)) {
            throw ChecksumMismatchException("SHA-256 が一致しません: ${asset.name}")
        }
        return bytes
    }

    private suspend fun downloadBytes(
        url: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): ByteArray = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .apply {
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw apiError(response.code, response.body?.string())
            val body = response.body ?: throw GitHubApiException(response.code, "応答が空です")
            val total = body.contentLength()
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            ByteArrayOutputStream(if (total in 1L..MAX_IN_MEMORY_BYTES) total.toInt() else 64 * 1024).use { out ->
                body.byteStream().use { input ->
                    var read: Int
                    var downloaded = 0L
                    while (true) {
                        read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, total)
                    }
                }
                out.toByteArray()
            }
        }
    }

    private fun apiError(code: Int, body: String?): GitHubApiException {
        val hint = when (code) {
            401, 403 -> "GitHub API のレート制限またはアクセス権を確認してください"
            404 -> "リポジトリまたは Release が見つかりません"
            else -> "GitHub API エラー"
        }
        val detail = body?.take(200)?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
        return GitHubApiException(code, "$hint (HTTP $code)${detail?.let { ": $it" } ?: ""}")
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.github.com"
        private const val ACCEPT_HEADER = "application/vnd.github+json"
        private const val API_VERSION = "2022-11-28"
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val MAX_IN_MEMORY_BYTES = 32L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
