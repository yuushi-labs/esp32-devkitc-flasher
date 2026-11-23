package com.yuushilabs.esp32flasher

import android.app.Application
import android.content.Context
import com.yuushilabs.esp32flasher.data.AppSettingsRepository
import com.yuushilabs.esp32flasher.github.FirmwareReleaseClient
import com.yuushilabs.esp32flasher.update.AppUpdateManager
import com.yuushilabs.esp32flasher.usb.UsbSerialManager
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val githubToken = AtomicReference<String?>(null)

    /** Completed once the saved settings (and with them the optional token) are loaded. */
    private val settingsLoaded = CompletableDeferred<Unit>()

    val settingsRepository = AppSettingsRepository(appContext, BuildConfig.DEFAULT_FIRMWARE_REPO)

    val releaseClient = FirmwareReleaseClient(tokenProvider = { githubToken.get() })

    val updateClient = FirmwareReleaseClient(tokenProvider = { githubToken.get() })

    val usbManager = UsbSerialManager(appContext)

    val updateManager = AppUpdateManager(
        context = appContext,
        client = updateClient,
        updateRepository = BuildConfig.UPDATE_REPO,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentVersionName = BuildConfig.VERSION_NAME,
    )

    init {
        appScope.launch {
            settingsRepository.settings.collect { settings ->
                githubToken.set(settings.githubToken.ifBlank { null })
                settingsLoaded.complete(Unit)
            }
        }
    }

    /** Waits until the stored token can be applied to the next GitHub request. */
    suspend fun awaitSettingsLoaded() {
        settingsLoaded.await()
    }
}

class Esp32FlasherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
