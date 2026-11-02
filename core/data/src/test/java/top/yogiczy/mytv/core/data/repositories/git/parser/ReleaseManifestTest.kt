package top.yogiczy.mytv.core.data.repositories.git.parser

import org.junit.Assert.*
import org.junit.Test

class ReleaseManifestTest {
    private fun manifest(vararg names: String) = """{"tag_name":"v1.0.0","body":"Release","assets":[${
        names.joinToString(",") { """{"name":"$it","browser_download_url":"https://example.invalid/$it"}""" }
    }]}"""
    private val arm32 = "liuguang-tv-1.0.0-armeabi-v7a.apk"
    private val arm64 = "liuguang-tv-1.0.0-arm64-v8a.apk"
    private val universal = "liuguang-tv-1.0.0-universal.apk"

    @Test fun arm64PrefersItsApkEvenWhenArm32IsFirst() {
        val result = ReleaseManifest.parse(manifest("SHA256SUMS.txt", arm32, universal, arm64), listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(result.downloadUrl.endsWith(arm64))
        assertEquals("1.0.0", result.version)
    }
    @Test fun a32BitOsUsesArm32EvenOnA64BitChip() {
        assertTrue(ReleaseManifest.parse(manifest(arm64, arm32, universal), listOf("armeabi-v7a", "armeabi")).downloadUrl.endsWith(arm32))
    }
    @Test fun x86FallsBackToUniversal() {
        assertTrue(ReleaseManifest.parse(manifest(arm32, arm64, universal), listOf("x86_64", "x86")).downloadUrl.endsWith(universal))
    }
    @Test fun assetNameCanBeDerivedFromDownloadUrl() {
        val data = """{"tag_name":"1.0.0","assets":[{"browser_download_url":"https://example.invalid/$universal?download=1"}]}"""
        assertEquals("1.0.0", ReleaseManifest.parse(data, listOf("x86")).version)
    }
    @Test(expected = IllegalStateException::class) fun anIncompatibleApkIsNotOffered() {
        ReleaseManifest.parse(manifest(arm64, "SHA256SUMS.txt"), listOf("armeabi-v7a"))
    }
}
