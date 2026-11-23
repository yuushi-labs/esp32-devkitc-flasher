package com.yuushilabs.esp32flasher.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.yuushilabs.esp32flasher.core.ReleaseAsset
import com.yuushilabs.esp32flasher.core.ReleaseVersion
import com.yuushilabs.esp32flasher.core.UpdateCheck
import com.yuushilabs.esp32flasher.core.UpdateDecision
import com.yuushilabs.esp32flasher.core.UpdateOutcome
import com.yuushilabs.esp32flasher.github.FirmwareReleaseClient
import com.yuushilabs.esp32flasher.github.RepoRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus

    data object NotModified : UpdateStatus

    data object NoRelease : UpdateStatus

    data class Available(
        val versionName: String,
        val releaseUrl: String?,
        val notes: String?,
        val apk: ReleaseAsset?,
    ) : UpdateStatus

    data class Failed(val message: String) : UpdateStatus
}

/** Self-update flow: check the app's own releases, download the APK and hand it to the installer. */
class AppUpdateManager(
    private val context: Context,
    private val client: FirmwareReleaseClient,
    private val updateRepository: String,
    private val currentVersionCode: Int,
    private val currentVersionName: String,
) {
    suspend fun checkForUpdate(forceRefresh: Boolean = false): UpdateStatus {
        val repository = RepoRef.parse(updateRepository)
            ?: return UpdateStatus.Failed("更新リポジトリの設定が不正です: $updateRepository")
        return try {
            val release = client.latestRelease(repository, forceRefresh) ?: return UpdateStatus.NoRelease
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val latest = ReleaseVersion(
                versionName = release.versionName,
                versionCode = UpdateDecision.parseVersionCode(release.body),
            )
            val check = UpdateCheck(
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName,
                latest = latest,
                releaseUrl = release.htmlUrl,
                apkUrl = apk?.downloadUrl,
                sha256 = apk?.sha256,
                notes = release.body,
            )
            when (val outcome = UpdateDecision.decide(check)) {
                is UpdateOutcome.Available -> UpdateStatus.Available(
                    versionName = outcome.latest.versionName,
                    releaseUrl = outcome.releaseUrl,
                    notes = outcome.notes,
                    apk = apk,
                )

                UpdateOutcome.NotModified -> UpdateStatus.NotModified
                UpdateOutcome.NoRelease -> UpdateStatus.NoRelease
                UpdateOutcome.UpToDate -> UpdateStatus.UpToDate
            }
        } catch (error: Exception) {
            UpdateStatus.Failed(error.message ?: "アップデートの確認に失敗しました")
        }
    }

    suspend fun downloadApk(
        asset: ReleaseAsset,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        val bytes = client.downloadAsset(asset, onProgress)
        val fileName = asset.name.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "esp32-flasher-update.apk"
        // Writing a multi-megabyte APK must not run on the main thread.
        return withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, UPDATES_DIRECTORY).apply { mkdirs() }
            File(directory, fileName).apply { writeBytes(bytes) }
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installApk(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun clearDownloads() {
        File(context.cacheDir, UPDATES_DIRECTORY).deleteRecursively()
    }

    private companion object {
        const val UPDATES_DIRECTORY = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
