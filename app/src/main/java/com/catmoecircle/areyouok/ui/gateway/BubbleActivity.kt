package com.catmoecircle.areyouok.ui.gateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.catmoecircle.areyouok.MainActivity
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewayRuntime
import com.catmoecircle.areyouok.gateway.service.GatewayService
import com.catmoecircle.areyouok.ui.chat.ChatScreen
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import kotlinx.coroutines.delay

/**
 * 通知气泡展开后的对话窗口。
 *
 * 按 Android 气泡 API 的要求，这个 Activity 在 Manifest 里声明为
 * `android:resizeableActivity="true"` + `android:allowEmbedded="true"`——
 * 缺任何一个系统都只会把气泡显示成普通通知。
 *
 * 气泡代表某个会话：这里只把 [GatewayService.EXTRA_SESSION_ID] 交给 GatewayRuntime
 * 切到对应会话，然后复用 [ChatScreen]。气泡收起时系统会销毁本 Activity。
 *
 * 进程被系统回收后，点气泡只会拉起这个 Activity（不会先拉起 App）：那时前台服务和
 * 长连接都不在，直接渲染聊天页就是一片空白。所以这里先补拉起服务/连接，界面在
 * 「会话就绪」之前显示加载态，超时则给出明确提示 + 「打开 App」入口，不再白屏。
 */
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = GatewayRuntime.get(this)
        val sessionId = intent?.getStringExtra(GatewayService.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
        sessionId?.let(runtime::requestOpenSession)
        // 冷进程：先把前台服务和网关连接拉起来，聊天页才有数据可显示。
        if (!runtime.repository.connection.value.isOnline) {
            GatewayService.start(this)
            runtime.connectLast()
        }
        setContent {
            AreYouOKTheme {
                BubbleConversation(sessionId)
            }
        }
    }
}

/** 气泡内的对话：一直挂载 [ChatScreen]（它负责消费 openSessionRequest 切会话），
 *  未就绪时用加载态盖住，避免用户对着一片空白。 */
@Composable
private fun BubbleConversation(sessionId: String?) {
    val context = LocalContext.current
    val runtime = remember(context) { GatewayRuntime.get(context) }
    val repository = runtime.repository
    val selectedSessionId by repository.selectedSessionId.collectAsState()
    val connection by repository.connection.collectAsState()
    // 只在「第一次把目标会话打开」之前挡一层加载态；之后不再遮挡——气泡里切不了会话，
    // 后续任何选中态变化都不该把内容重新盖成白屏。
    var initialReady by remember { mutableStateOf(sessionId == null) }
    var gaveUp by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, connection.isOnline) {
        if (sessionId == null) return@LaunchedEffect
        // 每次连上/重连都重新请求一次：ChatScreen 消费后才会真正切到目标会话。
        runtime.requestOpenSession(sessionId)
        if (!connection.isOnline) {
            GatewayService.start(context)
            runtime.connectLast()
        }
    }
    LaunchedEffect(sessionId) {
        delay(BUBBLE_READY_TIMEOUT_MS)
        gaveUp = true
    }
    LaunchedEffect(sessionId, connection.isOnline, selectedSessionId) {
        if (sessionId == null) {
            initialReady = true
            return@LaunchedEffect
        }
        if (connection.isOnline && selectedSessionId == sessionId) initialReady = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 恢复完整聊天界面：气泡里也能开侧边栏、切会话（实测系统放行后可以正常用）。
        ChatScreen()
        if (!initialReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    if (!gaveUp) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.bubble_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.bubble_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { openMainActivity(context, sessionId) }) {
                            Text(text = stringResource(R.string.bubble_open_app))
                        }
                    }
                }
            }
        }
    }
}

/** 气泡里彻底打不开时的兜底：带着会话 id 回到主界面。 */
private fun openMainActivity(context: Context, sessionId: String?) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        sessionId?.takeIf { it.isNotBlank() }?.let { putExtra(GatewayService.EXTRA_SESSION_ID, it) }
    }
    runCatching { context.startActivity(intent) }
}

/** 等连接/会话就绪的上限；超过就认为是真打不开（会话被删、网关离线）。 */
private const val BUBBLE_READY_TIMEOUT_MS = 6_000L
