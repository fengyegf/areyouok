package com.catmoecircle.areyouok

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.catmoecircle.areyouok.gateway.GatewayRuntime
import com.catmoecircle.areyouok.gateway.pairing.PairInviteParser
import com.catmoecircle.areyouok.gateway.service.GatewayService
import com.catmoecircle.areyouok.ui.chat.ChatScreen
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响前台服务 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val runtime = GatewayRuntime.get(this)
        handleIntent(intent, runtime)
        requestNotificationPermissionIfNeeded()
        runtime.startDiscovery()

        // 前台服务维持 /v1/stream 与审批通知；有上次使用的网关时会自动重连。
        GatewayService.start(this)

        setContent {
            AreYouOKTheme {
                ChatScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, GatewayRuntime.get(this))
    }

    override fun onStart() {
        super.onStart()
        // 「任务完成」提醒用：用户正看着某个会话时不打扰。
        GatewayRuntime.get(this).setAppVisible(true)
    }

    override fun onStop() {
        GatewayRuntime.get(this).setAppVisible(false)
        super.onStop()
    }

    /**
     * 处理通知/深链入口：
     * - 通知点击带 `sessionId` → 请求切到对应对话（审批 / 提问 / 任务完成通知都走这里）；
     * - `dshgw://pair?url=…&code=…&fp=…&n=…` → 打开设置页并预填配对区。
     */
    private fun handleIntent(intent: Intent?, runtime: GatewayRuntime) {
        if (intent == null) return
        // 读一次就移除：屏幕旋转/进程内重建复用同一个 Intent 时不要再次跳转。
        intent.getStringExtra(GatewayService.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(runtime::requestOpenSession)
        intent.removeExtra(GatewayService.EXTRA_SESSION_ID)

        val data = intent.data ?: return
        PairInviteParser.parse(data.toString())?.let { runtime.offerInvite(it) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
