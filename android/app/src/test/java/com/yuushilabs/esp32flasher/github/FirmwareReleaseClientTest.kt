package com.yuushilabs.esp32flasher.github

import com.yuushilabs.esp32flasher.core.FlashManifestParser
import com.yuushilabs.esp32flasher.core.ReleaseAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FirmwareReleaseClientTest {
    private lateinit var server: MockWebServer

    private val releaseJson = """
        [
          {
            "tag_name": "v0.2.0",
            "name": "0.2.0",
            "draft": false,
            "prerelease": false,
            "assets": [
              { "name": "flash-manifest.json", "browser_download_url": "https://x/manifest.json", "size": 10 },
              { "name": "esp32-merged.bin", "browser_download_url": "https://x/merged.bin", "size": 100 }
            ]
          }
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(cache: ReleaseCache = MemoryReleaseCache()): FirmwareReleaseClient =
        FirmwareReleaseClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            cache = cache,
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `lists releases and revalidates with etag`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("ETag", "\"etag-1\"")
                .setBody(releaseJson),
        )
        server.enqueue(MockResponse().setResponseCode(304))

        val client = client()
        val first = client.listReleases(RepoRef("owner", "repo"))
        assertEquals(1, first.releases.size)
        assertFalse(first.notModified)
        assertFalse(first.fromCache)
        assertEquals("v0.2.0", first.releases.first().tagName)

        val second = client.listReleases(RepoRef("owner", "repo"))
        assertTrue(second.notModified)
        assertTrue(second.fromCache)
        assertEquals(1, second.releases.size)

        val firstRequest = server.takeRequest()
        assertEquals("/repos/owner/repo/releases?per_page=20", firstRequest.path)
        assertNull(firstRequest.getHeader("If-None-Match"))
        assertEquals("application/vnd.github+json", firstRequest.getHeader("Accept"))
        val revalidation = server.takeRequest()
        assertEquals("\"etag-1\"", revalidation.getHeader("If-None-Match"))
    }

    @Test
    fun `force refresh ignores the cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"e\"").setBody(releaseJson))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"e\"").setBody(releaseJson))
        val client = client()
        client.listReleases(RepoRef("owner", "repo"))
        val refreshed = client.listReleases(RepoRef("owner", "repo"), forceRefresh = true)
        assertFalse(refreshed.notModified)
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `latest release returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertNull(client().latestRelease(RepoRef("owner", "missing")))
    }

    @Test
    fun `api errors surface as GitHubApiException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rate limited"}"""))
        val error = runCatching { client().listReleases(RepoRef("owner", "repo")) }.exceptionOrNull()
        assertTrue("unexpected error: $error", error is GitHubApiException)
        assertEquals(403, (error as GitHubApiException).statusCode)
        assertTrue(error.message.orEmpty().contains("403"))
    }

    @Test
    fun `downloads and verifies a sha256 digest`() = runTest {
        val bytes = "firmware-bytes".toByteArray()
        val digest = FlashManifestParser.sha256Hex(bytes)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val asset = ReleaseAsset(
            name = "fw.bin",
            downloadUrl = server.url("/fw.bin").toString(),
            sizeBytes = bytes.size.toLong(),
            digest = "sha256:$digest",
        )
        val downloaded = client().downloadAsset(asset)
        assertArrayEquals(bytes, downloaded)
        assertEquals(digest, asset.sha256)
    }

    @Test
    fun `download reports progress`() = runTest {
        val bytes = ByteArray(4096) { 7 }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val progress = mutableListOf<Pair<Long, Long>>()
        val asset = ReleaseAsset("fw.bin", server.url("/fw.bin").toString(), bytes.size.toLong())
        client().downloadAsset(asset) { read, total -> progress += read to total }
        assertTrue(progress.isNotEmpty())
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)
    }

    @Test
    fun `download rejects a checksum mismatch`() = runTest {
        val bytes = "payload".toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val asset = ReleaseAsset(
            name = "fw.bin",
            downloadUrl = server.url("/fw.bin").toString(),
            digest = "sha256:" + "0".repeat(64),
        )
        val error = runCatching { client().downloadAsset(asset) }.exceptionOrNull()
        assertTrue("unexpected error: $error", error is ChecksumMismatchException)
    }

    @Test
    fun `download rejects a size mismatch`() = runTest {
        val bytes = "payload".toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val asset = ReleaseAsset(
            name = "fw.bin",
            downloadUrl = server.url("/fw.bin").toString(),
            sizeBytes = 999,
        )
        val error = runCatching { client().downloadAsset(asset) }.exceptionOrNull()
        assertTrue("unexpected error: $error", error is ChecksumMismatchException)
    }

    @Test
    fun `fetches and parses a flash manifest`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"merged":{"file":"a.bin","address":"0x0"},"pins":[{"gpio":2,"label":"LED"}]}""",
            ),
        )
        val manifest = client().fetchManifest(
            ReleaseAsset("flash-manifest.json", server.url("/manifest.json").toString()),
        )
        assertEquals("a.bin", manifest.merged?.fileName)
        assertEquals(1, manifest.pins.size)
        assertEquals(2, manifest.pins.first().gpio)
    }

    @Test
    fun `parses repository references`() {
        assertEquals("owner/repo", RepoRef.parse("owner/repo")?.slug)
        assertEquals("owner/repo", RepoRef.parse(" https://github.com/owner/repo ")?.slug)
        assertEquals("owner/repo", RepoRef.parse("https://github.com/owner/repo.git")?.slug)
        assertNotNull(RepoRef.parse("yuushi-labs/esp32-devkitc-firmware"))
        assertNull(RepoRef.parse(""))
        assertNull(RepoRef.parse("owner"))
        assertNull(RepoRef.parse("owner/repo/extra"))
        assertNull(RepoRef.parse("own er/repo"))
    }
}
