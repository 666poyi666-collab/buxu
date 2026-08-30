package com.poyi.watchintervals.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.poyi.watchintervals.phone.ui.PhoneApp
import com.poyi.watchintervals.phone.ui.PhoneEvent
import com.poyi.watchintervals.phone.ui.PhoneViewModel
import kotlinx.coroutines.launch

/**
 * 手机端唯一 Activity 宿主。
 *
 * 本类只负责生命周期、系统栏、权限与跨 Activity 跳转;所有界面与状态由
 * [PhoneApp] 和 [PhoneViewModel] 承担。手表侧的 WorkoutService 仍是训练状态的唯一权威,
 * 这里只发送命令与读取快照。
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PhoneViewModel

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocationRelay()
    }

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.BLUETOOTH_SCAN] == true &&
            result[Manifest.permission.BLUETOOTH_CONNECT] == true
        ) {
            startCompanionService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        startForegroundService(Intent(this, PhonePlanBridgeService::class.java))
        viewModel = ViewModelProvider(this)[PhoneViewModel::class.java]

        setContent { PhoneApp(viewModel) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event -> handleEvent(event) }
            }
        }

        ensureBluetoothConnection()
        provisionCloudFromIntent(intent)
        viewModel.discoverWatch()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        viewModel.onPause()
        super.onPause()
    }

    private fun handleEvent(event: PhoneEvent) {
        when (event) {
            is PhoneEvent.Toast ->
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()

            is PhoneEvent.OpenWorkoutDetail -> startActivity(
                Intent(this, HistoryDetailActivity::class.java).putExtra("record", event.payload)
            )

            PhoneEvent.RequestLocationPermission -> {
                if (hasFineLocation()) startLocationRelay() else requestLocationPermissions()
            }

            PhoneEvent.RequestBluetoothPermission -> ensureBluetoothConnection()
        }
    }

    private fun ensureBluetoothConnection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                bluetoothPermissions.launch(missing.toTypedArray())
                return
            }
        }
        startCompanionService()
    }

    private fun startCompanionService() {
        runCatching {
            startForegroundService(Intent(this, PhoneCompanionService::class.java))
        }
    }

    /**
     * 位置中继只允许在前台启动:Android 14+ 从后台回调启动 location 类型前台服务会抛异常。
     */
    private fun startLocationRelay() {
        if (!hasFineLocation()) {
            requestLocationPermissions()
            return
        }
        runCatching {
            startForegroundService(Intent(this, PhoneLocationRelayService::class.java))
        }
    }

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissions() {
        if (hasFineLocation()) {
            startLocationRelay()
            return
        }
        locationPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun provisionCloudFromIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val endpoint = intent.getStringExtra("poyi_cloud_endpoint")
        val key = intent.getStringExtra("poyi_cloud_key")
        if (endpoint == null || key == null) return
        viewModel.provisionCloudFromIntent(endpoint, key)
        intent.removeExtra("poyi_cloud_endpoint")
        intent.removeExtra("poyi_cloud_key")
    }
}
