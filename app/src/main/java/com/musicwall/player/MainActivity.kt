package com.musicwall.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.musicwall.player.ui.MusicWallScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val player by viewModels<PlayerViewModel>()
    private val audioOutput = MutableStateFlow("音频输出")
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { MusicWallScreen(player, audioOutput, ::openAudioOutput) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { player.state.collect { renderWindow(it.wall, it.theme) } }
                launch {
                    while (isActive) {
                        audioOutput.value = audioOutputName()
                        delay(1_500)
                    }
                }
            }
        }
    }

    private var renderedWall: Boolean? = null
    private var renderedLightBars = false
    private var regularBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private fun renderWindow(wall: Boolean, theme: AppTheme) {
        val lightBars = theme == AppTheme.Apple
        if (renderedWall == wall && renderedLightBars == lightBars) return
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.isAppearanceLightStatusBars = lightBars
        insets.isAppearanceLightNavigationBars = lightBars
        renderedLightBars = lightBars
        if (renderedWall == wall) return
        renderedWall = wall
        if (wall) {
            regularBrightness = window.attributes.screenBrightness
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 0.24f }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = regularBrightness }
        }
    }

    private fun openAudioOutput() {
        if (Build.VERSION.SDK_INT >= 34) {
            val shown = runCatching { MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
            if (shown) return
        }
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun audioOutputName(): String {
        val manager = getSystemService(AudioManager::class.java) ?: return "音频输出"
        if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            return if (manager.isBluetoothA2dpOn) "蓝牙音频" else "音频输出"
        }
        val device = runCatching { manager.getAudioDevicesForAttributes(audioAttributes).firstOrNull() }.getOrNull()
            ?: return "音频输出"
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                device.productName.toString().ifBlank { "蓝牙音频" }
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "本机扬声器"
            else -> device.productName.toString().ifBlank { "音频输出" }
        }
    }
}
