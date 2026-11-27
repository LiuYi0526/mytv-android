package top.yogiczy.mytv.tv.ui.screen.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, expectedVersion: String) {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val packageManager = context.packageManager
    val installed = packageManager.getPackageInfo(context.packageName, flags)
    val update = checkNotNull(packageManager.getPackageArchiveInfo(file.path, flags)) {
        "无法读取更新包，文件可能不完整"
    }
    check(update.packageName == installed.packageName) { "更新包与当前应用的包名不一致" }
    check(update.versionName == expectedVersion) { "更新包版本与更新清单不一致" }
    check(PackageInfoCompat.getLongVersionCode(update) > PackageInfoCompat.getLongVersionCode(installed)) {
        "更新包版本没有高于当前版本"
    }
    val installedSigners = installed.currentSigners()
    check(installedSigners.isNotEmpty() && update.currentSigners() == installedSigners) {
        "更新包签名与当前应用不一致"
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.currentSigners(): Set<Signature> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else {
        signatures.orEmpty().toSet()
    }
