package com.github.jomof.dap.scaffold.locator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Drives [CodeLldbDownloaderImpl] without touching the network: a tiny
 * test subclass intercepts the GitHub JSON fetch and VSIX byte stream,
 * substituting an in-memory fake. Validates four real risks:
 *
 *  1. The fake-but-correct GitHub JSON shape parses into a usable
 *     [CodeLldbDownloaderImpl.Release] (asset list with names + URLs).
 *  2. The hand-rolled JSON walker tolerates the extra fields a real
 *     response carries (authors, dates, nested objects, escaped strings).
 *  3. `downloadAndExtract` actually unpacks the `extension/...` tree
 *     into the target directory, with payload bytes intact.
 *  4. `ensureInstalled` is a no-op when a matching install is already
 *     in the cache.
 */
class CodeLldbDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `extractStringField pulls top-level strings`() {
        val sample = """{"tag_name":"v1.12.2","other":"x"}"""
        assertEquals("v1.12.2", CodeLldbDownloaderImpl.extractStringField(sample, "tag_name"))
        assertEquals("x", CodeLldbDownloaderImpl.extractStringField(sample, "other"))
        assertNull(CodeLldbDownloaderImpl.extractStringField(sample, "missing"))
    }

    @Test fun `extractAssets walks the assets array and pulls name plus download url`() {
        val sample = """
            {
              "tag_name":"v1.12.2",
              "assets":[
                {"name":"codelldb-darwin-arm64.vsix","browser_download_url":"https://example.test/a.vsix","size":12345},
                {"name":"codelldb-linux-x64.vsix","browser_download_url":"https://example.test/b.vsix"}
              ]
            }
        """.trimIndent()
        val assets = CodeLldbDownloaderImpl.extractAssets(sample)
        assertEquals(2, assets.size)
        assertEquals("codelldb-darwin-arm64.vsix", assets[0].name)
        assertEquals("https://example.test/a.vsix", assets[0].downloadUrl)
        assertEquals("codelldb-linux-x64.vsix", assets[1].name)
        assertEquals("https://example.test/b.vsix", assets[1].downloadUrl)
    }

    @Test fun `extractAssets handles nested objects in asset entries`() {
        // Real GitHub responses nest an `uploader` object inside each
        // asset, plus a `digest` field that contains a colon. The
        // walker must not be fooled by either.
        val sample = """
            {"assets":[
              {
                "name":"codelldb-darwin-arm64.vsix",
                "uploader":{"login":"bot","id":1},
                "digest":"sha256:deadbeef",
                "browser_download_url":"https://example.test/a.vsix"
              }
            ]}
        """.trimIndent()
        val assets = CodeLldbDownloaderImpl.extractAssets(sample)
        assertEquals(1, assets.size)
        assertEquals("codelldb-darwin-arm64.vsix", assets[0].name)
        assertEquals("https://example.test/a.vsix", assets[0].downloadUrl)
    }

    @Test fun `extractVsix only unpacks entries under extension prefix`() {
        val vsix = buildVsix(tmp.newFile("fake.vsix").toPath()) {
            // VSIX always carries packaging metadata at the root that
            // we deliberately ignore.
            entry("extension.vsixmanifest", "<xml/>".toByteArray())
            entry("[Content_Types].xml", "<types/>".toByteArray())
            // The real payload lives under extension/.
            entry("extension/adapter/codelldb", "I am codelldb".toByteArray())
            entry("extension/lldb/lib/liblldb.dylib", "I am libldb".toByteArray())
            entry("extension/readme.md", "hi".toByteArray())
        }
        val out = tmp.newFolder("out").toPath()
        downloaderUnderHostDefaults().extractVsix(vsix, out)
        assertTrue(
            "adapter binary should be extracted",
            Files.exists(out.resolve("extension/adapter/codelldb")),
        )
        assertEquals(
            "I am codelldb",
            String(Files.readAllBytes(out.resolve("extension/adapter/codelldb"))),
        )
        assertTrue(
            "liblldb should be extracted",
            Files.exists(out.resolve("extension/lldb/lib/liblldb.dylib")),
        )
        assertTrue(
            "non-extension VSIX metadata should NOT be extracted",
            !Files.exists(out.resolve("extension.vsixmanifest")) &&
                !Files.exists(out.resolve("[Content_Types].xml")),
        )
    }

    @Test fun `extractVsix refuses zip-slip entries`() {
        // Hand-craft a malicious entry whose normalised path escapes
        // the target dir via "..". The downloader must refuse.
        val vsix = buildVsix(tmp.newFile("evil.vsix").toPath()) {
            entry("extension/../../escaped.txt", "pwn".toByteArray())
        }
        val out = tmp.newFolder("out").toPath()
        try {
            downloaderUnderHostDefaults().extractVsix(vsix, out)
            fail("expected IOException for zip-slip entry")
        } catch (e: IOException) {
            assertTrue("message should explain", e.message?.contains("outside target") == true)
        }
    }

    @Test fun `ensureInstalled is a no-op when cache already contains a binary`() {
        val cacheRoot = tmp.newFolder("cache").toPath()
        val os = "Mac OS X"
        // Pre-seed the cache with a fake v9.9.9 install so we can
        // assert the downloader doesn't try to hit the network.
        val installed = cacheRoot
            .resolve("v9.9.9")
            .resolve(CodeLldbAssetCatalog.adapterPath(os))
        val installedLib = cacheRoot
            .resolve("v9.9.9")
            .resolve(CodeLldbAssetCatalog.liblldbPath(os))
        Files.createDirectories(installed.parent)
        Files.createDirectories(installedLib.parent)
        Files.write(installed, byteArrayOf(0x7F))
        Files.write(installedLib, byteArrayOf(0x7F))
        installed.toFile().setExecutable(true)

        val downloader = object : CodeLldbDownloaderImpl(
            cacheRoot = cacheRoot,
            osName = os,
            osArch = "aarch64",
        ) {
            override fun fetchLatestReleaseJson(): String =
                fail("HTTP must not be called when cache is warm").let { "" }

            override fun openAssetStream(url: String): InputStream =
                fail("download must not be called when cache is warm").let { ByteArrayInputStream(ByteArray(0)) }
        }

        val result = downloader.ensureInstalled()
        assertEquals(installed.toAbsolutePath(), result.toAbsolutePath())
    }

    @Test fun `ensureInstalled fetches, extracts, and chmods on cold cache`() {
        val cacheRoot = tmp.newFolder("cache").toPath()
        val vsixBytes = buildVsixBytes {
            entry("extension/adapter/codelldb", "binary-bytes".toByteArray())
            entry("extension/lldb/lib/liblldb.dylib", "liblldb-bytes".toByteArray())
        }
        val downloader = object : CodeLldbDownloaderImpl(
            cacheRoot = cacheRoot,
            osName = "Mac OS X",
            osArch = "aarch64",
        ) {
            override fun fetchLatestReleaseJson(): String = """
                {
                  "tag_name":"v1.12.2",
                  "assets":[
                    {"name":"codelldb-darwin-arm64.vsix","browser_download_url":"http://example.test/codelldb-darwin-arm64.vsix"}
                  ]
                }
            """.trimIndent()

            override fun openAssetStream(url: String): InputStream {
                assertEquals("http://example.test/codelldb-darwin-arm64.vsix", url)
                return ByteArrayInputStream(vsixBytes)
            }
        }

        val result = downloader.ensureInstalled()
        assertEquals(
            cacheRoot.resolve("v1.12.2/extension/adapter/codelldb").toAbsolutePath(),
            result.toAbsolutePath(),
        )
        assertTrue("adapter should be executable", Files.isExecutable(result))
        assertEquals("binary-bytes", String(Files.readAllBytes(result)))
    }

    @Test fun `ensureInstalled fails fast on unknown host`() {
        val downloader = CodeLldbDownloaderImpl(
            cacheRoot = tmp.newFolder("cache").toPath(),
            osName = "Plan9",
            osArch = "sparc",
        )
        try {
            downloader.ensureInstalled()
            fail("expected IOException for unknown host")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Plan9") == true)
        }
    }

    @Test fun `existingInstall returns null for empty cache root`() {
        val downloader = CodeLldbDownloaderImpl(cacheRoot = tmp.newFolder("cache").toPath())
        assertNull(downloader.existingInstall())
    }

    @Test fun `existingInstall picks version based on SemVer comparison instead of lexical sorting`() {
        val cacheRoot = tmp.newFolder("cache").toPath()
        // Create directory structures for v1.2.0 and v1.10.0
        val v2Dir = cacheRoot.resolve("v1.2.0")
        val v10Dir = cacheRoot.resolve("v1.10.0")
        val os = System.getProperty("os.name") ?: "Mac OS X"
        
        val v2Binary = v2Dir.resolve(CodeLldbAssetCatalog.adapterPath(os))
        val v10Binary = v10Dir.resolve(CodeLldbAssetCatalog.adapterPath(os))
        val v2Lib = v2Dir.resolve(CodeLldbAssetCatalog.liblldbPath(os))
        val v10Lib = v10Dir.resolve(CodeLldbAssetCatalog.liblldbPath(os))
        
        Files.createDirectories(v2Binary.parent)
        Files.createDirectories(v10Binary.parent)
        Files.createDirectories(v2Lib.parent)
        Files.createDirectories(v10Lib.parent)
        Files.write(v2Binary, byteArrayOf(0x02))
        Files.write(v10Binary, byteArrayOf(0x10))
        Files.write(v2Lib, byteArrayOf(0x02))
        Files.write(v10Lib, byteArrayOf(0x10))
        v2Binary.toFile().setExecutable(true)
        v10Binary.toFile().setExecutable(true)
        
        val downloader = CodeLldbDownloaderImpl(cacheRoot = cacheRoot, osName = os)
        val result = downloader.existingInstall()
        
        // Should resolve to the semantically higher v1.10.0 (lexical sort would pick v1.2.0)
        assertEquals(v10Binary.toAbsolutePath(), result?.toAbsolutePath())
    }

    @Test fun `existingInstall skips incomplete higher-version cache entries`() {
        val cacheRoot = tmp.newFolder("cache").toPath()
        val downloader = CodeLldbDownloaderImpl(cacheRoot = cacheRoot, osName = "Linux", osArch = "x86_64")

        val partialAdapter = cacheRoot.resolve("v9.9.9").resolve(CodeLldbAssetCatalog.adapterPath("Linux"))
        Files.createDirectories(partialAdapter.parent)
        Files.write(partialAdapter, byteArrayOf(0x09))
        partialAdapter.toFile().setExecutable(true)

        val completeAdapter = cacheRoot.resolve("v1.0.0").resolve(CodeLldbAssetCatalog.adapterPath("Linux"))
        val completeLib = cacheRoot.resolve("v1.0.0").resolve(CodeLldbAssetCatalog.liblldbPath("Linux"))
        Files.createDirectories(completeAdapter.parent)
        Files.createDirectories(completeLib.parent)
        Files.write(completeAdapter, byteArrayOf(0x01))
        Files.write(completeLib, byteArrayOf(0x01))
        completeAdapter.toFile().setExecutable(true)

        assertEquals(completeAdapter.toAbsolutePath(), downloader.existingInstall()?.toAbsolutePath())
    }

    @Test fun `adapterBinary and liblldbBinary compose paths off the version dir`() {
        val downloader = CodeLldbDownloaderImpl(
            cacheRoot = tmp.newFolder("cache").toPath(),
            osName = "Linux",
            osArch = "x86_64",
        )
        val versionDir = tmp.newFolder("v1.0.0").toPath()
        assertEquals(
            versionDir.resolve("extension/adapter/codelldb"),
            downloader.adapterBinary(versionDir),
        )
        assertEquals(
            versionDir.resolve("extension/lldb/lib/liblldb.so"),
            downloader.liblldbBinary(versionDir),
        )
    }

    private fun downloaderUnderHostDefaults(): CodeLldbDownloaderImpl =
        CodeLldbDownloaderImpl(cacheRoot = tmp.newFolder("default-cache").toPath())

    private fun buildVsix(target: Path, build: VsixBuilder.() -> Unit): Path {
        Files.write(target, buildVsixBytes(build))
        return target
    }

    private fun buildVsixBytes(build: VsixBuilder.() -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            VsixBuilder(zip).build()
        }
        return out.toByteArray()
    }

    private class VsixBuilder(private val zip: ZipOutputStream) {
        fun entry(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
