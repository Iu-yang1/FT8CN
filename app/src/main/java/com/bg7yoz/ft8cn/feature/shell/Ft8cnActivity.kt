package com.bg7yoz.ft8cn.feature.shell

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.bluetooth.BluetoothStateBroadcastReceive
import com.bg7yoz.ft8cn.callsign.CallsignDatabase
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.core.time.AndroidGnssTimeDiscipline
import com.bg7yoz.ft8cn.database.DatabaseOpr
import com.bg7yoz.ft8cn.database.OnAfterQueryConfig
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.log.ImportSharedLogs
import com.bg7yoz.ft8cn.log.OnShareLogEvents
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid
import com.bg7yoz.ft8cn.ui.ToastMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class RuntimeModeSettings(
    val emeModeEnabled: Boolean,
    val satelliteModeEnabled: Boolean,
    val previousFtxMode: Int,
    val q65Submode: Int,
    val q65TrPeriodSeconds: Int,
    val emeBaseFrequencyHz: Long,
)

/**
 * FT8CN 唯一产品入口。Activity 只管理权限和长生命周期输入，页面状态由 Compose 与各控制器持有。
 */
class Ft8cnActivity : AppCompatActivity() {
    companion object {
        /** 内部页面直达入口；导航壳会再次校验 route，且不会覆盖用户保存的页面。 */
        const val EXTRA_INITIAL_DESTINATION = "com.bg7yoz.ft8cn.extra.INITIAL_DESTINATION"
    }

    private lateinit var mainViewModel: MainViewModel
    private lateinit var gnssTimeDiscipline: AndroidGnssTimeDiscipline
    private val graph by lazy { FeatureAppGraph.from(applicationContext) }
    private var gnssEnabled = true
    private var runtimeModeSettingsLoaded = false
    private var bluetoothReceiver: BluetoothStateBroadcastReceive? = null
    private var importedUri: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            mainViewModel.ensureAudioCaptureRunning()
        }
        updateGnssState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeneralVariables.getInstance().setMainContext(applicationContext)
        mainViewModel = MainViewModel.getInstance(this)
        gnssTimeDiscipline = AndroidGnssTimeDiscipline(applicationContext)
        ToastMessage.getInstance()
        registerBluetoothReceiver()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        loadSavedConfiguration()
        requestRuntimePermissions()

        lifecycleScope.launch {
            graph.settings.state
                .map { it.gnssTimeEnabled }
                .distinctUntilChanged()
                .collect {
                    gnssEnabled = it
                    updateGnssState()
                }
        }
        lifecycleScope.launch {
            graph.settings.state
                .map { settings ->
                    RuntimeModeSettings(
                        emeModeEnabled = settings.emeModeEnabled,
                        satelliteModeEnabled = settings.satelliteModeEnabled,
                        previousFtxMode = settings.previousFtxMode,
                        q65Submode = settings.q65Submode,
                        q65TrPeriodSeconds = settings.q65TrPeriodSeconds,
                        emeBaseFrequencyHz = settings.emeBaseFrequencyHz,
                    )
                }
                .distinctUntilChanged()
                .collect { settings ->
                    GeneralVariables.setQ65Configuration(settings.q65Submode, settings.q65TrPeriodSeconds)
                    GeneralVariables.emeBaseFrequencyHz = settings.emeBaseFrequencyHz
                    applyPersistedOperatingMode(
                        settings.emeModeEnabled,
                        settings.satelliteModeEnabled,
                        settings.previousFtxMode,
                    )
                    // 首次 DataStore 快照落地前，不允许 LiveData 的默认 FT8 值覆盖已保存的 FT4。
                    runtimeModeSettingsLoaded = true
                }
        }
        GeneralVariables.mutableSignalMode.observe(this) { observedMode ->
            val mode = observedMode ?: return@observe
            if (!runtimeModeSettingsLoaded ||
                GeneralVariables.getOperatingProfile() != GeneralVariables.OPERATING_PROFILE_NORMAL ||
                mode !in com.bg7yoz.ft8cn.FT8Common.FT8_MODE..com.bg7yoz.ft8cn.FT8Common.FT4_MODE
            ) return@observe

            lifecycleScope.launch {
                val current = graph.settings.state.first()
                if (current.previousFtxMode != mode) {
                    graph.settings.setPreviousFtxMode(mode)
                }
            }
        }

        setContentView(
            androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    Ft8cnTheme {
                        Ft8cnFeatureShell(
                            mainViewModel = mainViewModel,
                            initialDestinationRoute = intent.getStringExtra(EXTRA_INITIAL_DESTINATION),
                        )
                    }
                }
            },
        )

        routeCompatibilityIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeCompatibilityIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            mainViewModel.ensureAudioCaptureRunning()
        }
        updateGnssState()
    }

    override fun onStop() {
        gnssTimeDiscipline.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) graph.radioTransmitBridge.emergencyStop()
        unregisterBluetoothReceiver()
        super.onDestroy()
    }

    private fun loadSavedConfiguration() {
        if (mainViewModel.configIsLoaded) return

        // 产品入口必须完整加载旧数据库配置，否则波段表会只剩当前频率。
        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(applicationContext)
        }
        mainViewModel.databaseOpr.getQslDxccToMap()
        DatabaseOpr.GetCallsignMapGrid(mainViewModel.databaseOpr.db).execute()
        mainViewModel.getFollowCallsignsFromDataBase()
        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(applicationContext, null, 1)
        }

        mainViewModel.databaseOpr.getAllConfigParameter(object : OnAfterQueryConfig {
            override fun doOnBeforeQueryConfig(KeyName: String?) = Unit

            override fun doOnAfterQueryConfig(KeyName: String?, Value: String?) {
                if (KeyName != null) return
                mainViewModel.configIsLoaded = true
                mainViewModel.refreshRecorderSampleRate()
                MaidenheadGrid.getMyMaidenheadGrid(applicationContext)
                    .takeIf(String::isNotBlank)
                    ?.let { savedGrid ->
                        GeneralVariables.setMyMaidenheadGrid(savedGrid)
                        mainViewModel.databaseOpr.writeConfig("grid", savedGrid, null)
                    }
                mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay)
                if (GeneralVariables.ntpEnable) mainViewModel.syncNtpTime()
                lifecycleScope.launch {
                    val settings = graph.settings.state.first()
                    applyPersistedOperatingMode(
                        settings.emeModeEnabled,
                        settings.satelliteModeEnabled,
                        settings.previousFtxMode,
                    )
                }
            }
        })
    }

    private fun applyPersistedOperatingMode(
        emeEnabled: Boolean,
        satelliteEnabled: Boolean,
        previousFtxMode: Int,
    ) {
        val targetProfile = when {
            emeEnabled -> GeneralVariables.OPERATING_PROFILE_Q65_EME
            satelliteEnabled -> GeneralVariables.OPERATING_PROFILE_SATELLITE_FT4
            else -> GeneralVariables.OPERATING_PROFILE_NORMAL
        }
        val targetMode = when (targetProfile) {
            GeneralVariables.OPERATING_PROFILE_Q65_EME -> com.bg7yoz.ft8cn.FT8Common.Q65_MODE
            GeneralVariables.OPERATING_PROFILE_SATELLITE_FT4 -> com.bg7yoz.ft8cn.FT8Common.FT4_MODE
            else -> previousFtxMode.coerceIn(
                com.bg7yoz.ft8cn.FT8Common.FT8_MODE,
                com.bg7yoz.ft8cn.FT8Common.FT4_MODE,
            )
        }
        if (GeneralVariables.getOperatingProfile() == targetProfile &&
            GeneralVariables.getSignalMode() == targetMode
        ) return

        mainViewModel.ft8TransmitSignal?.setActivated(false)
        if (targetProfile != GeneralVariables.OPERATING_PROFILE_Q65_EME) {
            mainViewModel.ft8TransmitSignal?.stopQ65Sequence("离开 Q65 EME 模式")
        }
        GeneralVariables.setOperatingProfile(targetProfile)
        GeneralVariables.setSignalMode(targetMode)
        mainViewModel.ft8SignalListener?.restartByCurrentMode()
        mainViewModel.ft8TransmitSignal?.apply {
            restartByCurrentMode()
            if (targetProfile == GeneralVariables.OPERATING_PROFILE_Q65_EME) prepareQ65ReceiveOnly()
        }
        mainViewModel.clearTransmittingMessage()
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filterNot(::hasPermission)
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateGnssState() {
        if (gnssEnabled && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            gnssTimeDiscipline.start()
        } else {
            gnssTimeDiscipline.stop()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 保留旧入口行为，但直接在当前产品 Activity 中处理，避免跳出 Compose。 */
    private fun routeCompatibilityIntent(source: Intent?) {
        val action = source?.action ?: return
        if (action == Intent.ACTION_MAIN) return
        if (action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            mainViewModel.getUsbDevice()
            return
        }
        if (action == Intent.ACTION_VIEW) {
            importSharedLogs(source)
        }
    }

    private fun importSharedLogs(source: Intent) {
        val uri = source.data ?: return
        val uriKey = uri.toString()
        if (uriKey == importedUri || mainViewModel.mutableImportShareRunning.value == true) return
        importedUri = uriKey
        val input = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        if (input == null) {
            ToastMessage.show("无法读取导入文件")
            return
        }
        mainViewModel.mutableImportShareRunning.postValue(true)
        runCatching {
            ImportSharedLogs(mainViewModel).doImport(input, object : OnShareLogEvents {
                override fun onPreparing(info: String) {
                    mainViewModel.mutableShareInfo.postValue(info)
                }

                override fun onShareStart(count: Int, info: String) {
                    mainViewModel.mutableSharePosition.postValue(0)
                    mainViewModel.mutableShareCount.postValue(count)
                    mainViewModel.mutableShareInfo.postValue(info)
                }

                override fun onShareProgress(count: Int, position: Int, info: String): Boolean {
                    mainViewModel.mutableSharePosition.postValue(position)
                    mainViewModel.mutableShareCount.postValue(count)
                    mainViewModel.mutableShareInfo.postValue(info)
                    return mainViewModel.mutableImportShareRunning.value == true
                }

                override fun afterGet(count: Int, info: String) {
                    input.close()
                    mainViewModel.mutableShareInfo.postValue(info)
                    mainViewModel.mutableImportShareRunning.postValue(false)
                }

                override fun onShareFailed(info: String) {
                    input.close()
                    mainViewModel.mutableShareInfo.postValue(info)
                    mainViewModel.mutableImportShareRunning.postValue(false)
                    ToastMessage.show(info)
                }
            })
        }.onFailure {
            input.close()
            mainViewModel.mutableImportShareRunning.postValue(false)
            ToastMessage.show("导入失败：${it.message}")
        }
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return
        bluetoothReceiver = BluetoothStateBroadcastReceive(applicationContext, mainViewModel)
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (mainViewModel.isBTConnected) mainViewModel.setBlueToothOn()
    }

    private fun unregisterBluetoothReceiver() {
        val receiver = bluetoothReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
        bluetoothReceiver = null
    }
}
