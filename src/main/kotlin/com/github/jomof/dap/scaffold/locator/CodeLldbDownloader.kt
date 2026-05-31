package com.github.jomof.dap.scaffold.locator

import com.github.jomof.dap.scaffold.locator.CodeLldbDownloaderImpl.Companion.SAFE_ENTRY_PREFIX
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Fetches and installs a CodeLLDB release from the same source lsp4ij
 * uses — the [vadimcn/codelldb GitHub releases](https://github.com/vadimcn/codelldb/releases).
 *
 * Resolution and extraction are deliberately split:
 *  - [latestRelease] queries the GitHub REST API for the most recent
 *    non-prerelease tag.
 *  - [downloadAndExtract] fetches the OS+arch-specific VSIX (a plain
 *    zip), unpacks it under a per-version directory inside [cacheRoot],
 *    and chmods the adapter binary executable.
 *  - [ensureInstalled] is the one-shot entry point: returns the cached
 *    adapter path if a matching version is already on disk, otherwise
 *    drives the download.
 *
 * The class is open-by-default for tests (so we can substitute an
 * in-memory HTTP fake and a tiny pre-built zip), but the production
 * `object` [CodeLldbDownloader] is what callers use.
 */
open class CodeLldbDownloaderImpl(
    /** Root directory under which per-version installs live. */
    val cacheRoot: Path = defaultCacheRoot(),
    /**
     * Host's OS name (defaults to `os.name`). Surfaced as a parameter so
     * tests can simulate Linux/Windows lookups on a macOS workstation.
     */
    val osName: String = System.getProperty("os.name") ?: "",
    /** Host's CPU architecture (defaults to `os.arch`). */
    val osArch: String = System.getProperty("os.arch") ?: "",
    /** HTTP client; overrideable so tests can stub it out. */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {

    /**
     * Returns the cached adapter path if a matching version is already
     * present, otherwise downloads the latest release into [cacheRoot]
     * and returns the freshly-extracted path.
     */
    @Throws(IOException::class)
    fun ensureInstalled(): Path {
        val asset = CodeLldbAssetCatalog.assetName(osName, osArch)
            ?: throw IOException(
                "CodeLLDB does not publish a build for os=$osName arch=$osArch; " +
                    "set DAP_CODELLDB to a hand-installed binary.",
            )
        existingInstall()?.let { return it }
        val release = latestRelease()
        val asset0 = release.assets.firstOrNull { it.name == asset }
            ?: throw IOException(
                "CodeLLDB release ${release.tagName} does not contain expected asset $asset",
            )
        val target = cacheRoot.resolve(release.tagName)
        downloadAndExtract(asset0.downloadUrl, target)
        markExecutables(target)
        return completeInstall(target)
            ?: throw IOException("Downloaded CodeLLDB install is incomplete: missing adapter or bundled liblldb.")
    }

    /**
     * Looks for an already-extracted CodeLLDB under [cacheRoot]/<tag>/
     * with a runnable adapter binary. We don't try to compare against
     * the latest tag — once cached, any version is good enough for the
     * caller. They can blow away the cache to force an upgrade.
     */
    fun existingInstall(): Path? {
        val root = cacheRoot.toFile()
        if (!root.isDirectory) return null
        return root.listFiles { f -> f.isDirectory }
            ?.sortedWith { f1, f2 -> compareSemVer(f2.name, f1.name) }
            ?.firstNotNullOfOrNull { dir ->
                completeInstall(dir.toPath())
            }
    }

    /** Path of the adapter binary inside an extracted install rooted at [versionDir]. */
    fun adapterBinary(versionDir: Path): Path =
        versionDir.resolve(CodeLldbAssetCatalog.adapterPath(osName))

    /** Path of the bundled `liblldb` inside an extracted install rooted at [versionDir]. */
    fun liblldbBinary(versionDir: Path): Path =
        versionDir.resolve(CodeLldbAssetCatalog.liblldbPath(osName))

    private fun completeInstall(versionDir: Path): Path? {
        val adapter = adapterBinary(versionDir)
        val liblldb = liblldbBinary(versionDir)
        return if (Files.isExecutable(adapter) && Files.exists(liblldb)) adapter else null
    }

    // ------------------------------------------------------------------
    // GitHub REST: latest release lookup.
    //
    // Open for tests; production code uses the real api.github.com.
    // ------------------------------------------------------------------

    /** Parsed shape of a GitHub release relevant to our use. */
    data class Release(val tagName: String, val assets: List<Asset>)
    data class Asset(val name: String, val downloadUrl: String)

    protected open fun fetchLatestReleaseJson(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(RELEASES_LATEST_URL))
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("GitHub releases query failed: HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    /**
     * Fetches and parses the latest CodeLLDB release. Uses a tiny
     * hand-rolled JSON walker rather than dragging in a parser
     * dependency — we only need three fields.
     */
    open fun latestRelease(): Release {
        val body = fetchLatestReleaseJson()
        val tag = extractStringField(body, "tag_name")
            ?: throw IOException("Could not find tag_name in GitHub response")
        return Release(tag, extractAssets(body))
    }

    // ------------------------------------------------------------------
    // HTTP fetch + zip extract.
    // ------------------------------------------------------------------

    protected open fun openAssetStream(url: String): InputStream {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IOException("Asset download failed: HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    /**
     * Downloads the VSIX at [downloadUrl] and extracts it into [targetDir],
     * which is created if necessary. Files outside the [SAFE_ENTRY_PREFIX]
     * are skipped — every entry we care about lives under `extension/`.
     */
    fun downloadAndExtract(downloadUrl: String, targetDir: Path) {
        Files.createDirectories(targetDir)
        // Download to a temp file first; extracting from a streaming
        // HTTP body locks us into single-pass and complicates retries.
        val tmpFile = Files.createTempFile("codelldb-", ".vsix")
        try {
            openAssetStream(downloadUrl).use { input ->
                Files.copy(input, tmpFile, StandardCopyOption.REPLACE_EXISTING)
            }
            extractVsix(tmpFile, targetDir)
        } finally {
            runCatching { Files.deleteIfExists(tmpFile) }
        }
    }

    /**
     * Extracts a VSIX (which is just a Zip) into [targetDir]. The VSIX
     * layout always includes a top-level `extension/` folder; we
     * preserve it because the rest of our code (and lsp4ij) walks it
     * with the prefix intact.
     */
    fun extractVsix(vsix: Path, targetDir: Path) {
        Files.newInputStream(vsix).use { rawInput ->
            ZipInputStream(rawInput).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.startsWith(SAFE_ENTRY_PREFIX)) {
                        val out = targetDir.resolve(entry.name).normalize()
                        // Defence against zip-slip: refuse entries that
                        // resolve outside the target directory.
                        if (!out.startsWith(targetDir)) {
                            throw IOException("Refusing zip entry outside target: ${entry.name}")
                        }
                        Files.createDirectories(out.parent)
                        Files.newOutputStream(out).use { os -> zip.copyTo(os) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun markExecutables(targetDir: Path) {
        Files.walk(targetDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val pathStr = file.toString().replace('\\', '/')
                    if (EXECUTABLE_PATH_MARKERS.any { pathStr.contains(it) }) {
                        runCatching { file.toFile().setExecutable(true) }
                    }
                }
        }
    }

    companion object {
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/vadimcn/codelldb/releases/latest"

        /**
         * Only entries under this prefix are extracted. VSIX files
         * always store payloads under `extension/`; anything else is
         * VSIX packaging metadata we don't need.
         */
        private const val SAFE_ENTRY_PREFIX = "extension/"

        /**
         * Path fragments (normalised to `/`) whose files must be marked
         * executable after extraction — the adapter binary and the
         * bundled lldb tools/libraries.
         */
        private val EXECUTABLE_PATH_MARKERS = listOf(
            "/adapter/",
            "/lldb/bin/",
            "/lldb/lib/",
            "/bin/",
        )

        /**
         * Default cache root: `~/.cache/dapij/codelldb`. Chosen
         * to avoid stepping on `~/.lsp4ij/dap/codelldb` (which lsp4ij
         * manages) while remaining stable across runs.
         */
        fun defaultCacheRoot(): Path =
            Path.of(System.getProperty("user.home"), ".cache", "dapij", "codelldb")

        // Minimal JSON walker. We only need to pull a few fields out of
        // a single object; a full parser dependency would be overkill.
        // The shape we care about is fixed by the GitHub REST API.

        internal fun extractStringField(json: String, field: String): String? {
            val needle = "\"$field\""
            var i = json.indexOf(needle)
            if (i < 0) return null
            i = json.indexOf(':', i + needle.length)
            if (i < 0) return null
            i = json.indexOf('"', i + 1)
            if (i < 0) return null
            val end = json.indexOf('"', i + 1)
            if (end < 0) return null
            return json.substring(i + 1, end)
        }

        internal fun extractAssets(json: String): List<Asset> {
            val assetsKey = "\"assets\""
            val assetsAt = json.indexOf(assetsKey)
            if (assetsAt < 0) return emptyList()
            // assets is an array of objects; walk balanced braces.
            val open = json.indexOf('[', assetsAt + assetsKey.length)
            if (open < 0) return emptyList()
            val close = findMatching(json, open, '[', ']')
            if (close < 0) return emptyList()
            val body = json.substring(open + 1, close)
            val out = mutableListOf<Asset>()
            var cursor = 0
            while (cursor < body.length) {
                val objOpen = body.indexOf('{', cursor)
                if (objOpen < 0) break
                val objClose = findMatching(body, objOpen, '{', '}')
                if (objClose < 0) break
                val obj = body.substring(objOpen, objClose + 1)
                val name = extractStringField(obj, "name")
                val downloadUrl = extractStringField(obj, "browser_download_url")
                if (name != null && downloadUrl != null) {
                    out += Asset(name, downloadUrl)
                }
                cursor = objClose + 1
            }
            return out
        }

        private fun findMatching(s: String, openAt: Int, open: Char, close: Char): Int {
            var depth = 0
            var inString = false
            var escape = false
            for (i in openAt until s.length) {
                val c = s[i]
                if (escape) { escape = false; continue }
                if (c == '\\') { escape = true; continue }
                if (c == '"') { inString = !inString; continue }
                if (inString) continue
                if (c == open) depth++
                else if (c == close) {
                    depth--
                    if (depth == 0) return i
                }
            }
            return -1
        }

        internal fun compareSemVer(v1: String, v2: String): Int {
            val clean1 = v1.removePrefix("v").split('.')
            val clean2 = v2.removePrefix("v").split('.')
            for (i in 0 until minOf(clean1.size, clean2.size)) {
                val num1 = clean1[i].toIntOrNull() ?: 0
                val num2 = clean2[i].toIntOrNull() ?: 0
                val comp = num1.compareTo(num2)
                if (comp != 0) return comp
            }
            return clean1.size.compareTo(clean2.size)
        }
    }
}

/** Production singleton; wraps the open class with default constructor args. */
object CodeLldbDownloader : CodeLldbDownloaderImpl()
