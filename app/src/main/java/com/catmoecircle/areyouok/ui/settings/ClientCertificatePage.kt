package com.catmoecircle.areyouok.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewayRuntime
import com.catmoecircle.areyouok.gateway.cert.ClientCertificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Lock

/** 页面底部的导入/删除结果提示（成功走次要色，失败走错误色）。 */
private sealed interface CertificateNotice {
    val message: String

    class Success(override val message: String) : CertificateNotice

    class Error(override val message: String) : CertificateNotice
}

/**
 * 「客户端证书」子页（契约 §7.2）：说明文案 + 已导入证书列表 + 从文件导入。
 *
 * 公网边强制双向 TLS：这里管理的是握手时由 [com.catmoecircle.areyouok.gateway.cert.ClientCerts]
 * 出示的证书。数据全部走 [GatewayRuntime]（自动领证在局域网连接时静默完成，页面只负责
 * 展示与手动导入/删除）。
 *
 * 私钥与密码只存在 PKCS#12 里，本页不读取、不打印：导入失败只展示异常里的中文消息。
 */
@Composable
internal fun SettingsClientCertificatePage(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val runtime = remember(context) { GatewayRuntime.get(context) }
    val scope = rememberCoroutineScope()

    var certificates by remember { mutableStateOf<List<ClientCertificate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var pendingDocument by remember { mutableStateOf<ByteArray?>(null) }
    var passwordDialogOpen by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<ClientCertificate?>(null) }
    var notice by remember { mutableStateOf<CertificateNotice?>(null) }

    fun refreshCertificates() {
        scope.launch {
            certificates = withContext(Dispatchers.IO) {
                runCatching { runtime.clientCerts.list() }.getOrDefault(emptyList())
            }.sortedByDescending { it.notAfter }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refreshCertificates() }

    // 系统文件选择器：.p12/.pfx 的 MIME 并不统一，加 */* 兜底，避免部分文件管理器
    // 把 .p12 标成 application/octet-stream 后选不中。
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        notice = null
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                notice = CertificateNotice.Error(
                    context.getString(R.string.gateway_cert_file_unreadable),
                )
            } else {
                pendingDocument = bytes
                passwordDialogOpen = true
            }
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.gateway_cert_title),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.gateway_cert_intro),
            color = colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )

        SettingsGroup(title = stringResource(R.string.gateway_cert_section_list)) {
            when {
                loading -> SettingsNavRow(
                    icon = MiuixIcons.Regular.Lock,
                    title = stringResource(R.string.gateway_cert_loading),
                    showChevron = false,
                    enabled = false,
                )

                certificates.isEmpty() -> SettingsNavRow(
                    icon = MiuixIcons.Regular.Lock,
                    title = stringResource(R.string.gateway_cert_empty),
                    description = stringResource(R.string.gateway_cert_empty_desc),
                    showChevron = false,
                    enabled = false,
                )

                else -> certificates.forEach { certificate ->
                    ClientCertificateRow(
                        certificate = certificate,
                        onDelete = { pendingRemoval = certificate },
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.gateway_cert_section_import)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.AddCircle,
                title = stringResource(
                    if (importing) R.string.gateway_cert_import_working else R.string.gateway_cert_import,
                ),
                description = stringResource(R.string.gateway_cert_import_desc),
                enabled = !importing,
                onClick = {
                    filePicker.launch(arrayOf("application/x-pkcs12", "*/*"))
                },
            )
        }

        notice?.let { current ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = current.message,
                color = if (current is CertificateNotice.Error) colorScheme.error else colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (passwordDialogOpen) {
        CertificatePasswordDialog(
            importing = importing,
            onDismiss = {
                if (!importing) {
                    passwordDialogOpen = false
                    pendingDocument = null
                }
            },
            onConfirm = { password ->
                val bytes = pendingDocument
                if (bytes != null) {
                    importing = true
                    notice = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { runtime.importClientCertificate(bytes, password) }
                        }
                        importing = false
                        passwordDialogOpen = false
                        pendingDocument = null
                        outcome.fold(
                            onSuccess = { certificate ->
                                val label = certificateDisplayLabel(certificate.label, certificate.subject)
                                    ?: context.getString(R.string.gateway_cert_label_fallback)
                                notice = CertificateNotice.Success(
                                    context.getString(R.string.gateway_cert_import_success, label),
                                )
                                refreshCertificates()
                            },
                            onFailure = { error ->
                                notice = CertificateNotice.Error(
                                    context.getString(
                                        R.string.gateway_cert_import_failed,
                                        error.message?.takeIf { it.isNotBlank() }
                                            ?: context.getString(R.string.gateway_cert_error_unknown),
                                    ),
                                )
                            },
                        )
                    }
                }
            },
        )
    }

    pendingRemoval?.let { certificate ->
        val label = certificateDisplayLabel(certificate.label, certificate.subject)
            ?: context.getString(R.string.gateway_cert_label_fallback)
        CertificateDeleteDialog(
            label = label,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                scope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        runCatching { runtime.removeClientCertificate(certificate.serial) }
                            .getOrDefault(false)
                    }
                    notice = if (removed) {
                        CertificateNotice.Success(context.getString(R.string.gateway_cert_removed))
                    } else {
                        CertificateNotice.Error(context.getString(R.string.gateway_cert_remove_failed))
                    }
                    if (removed) refreshCertificates()
                }
            },
        )
    }
}

/** 一张证书的行：label + serial 前 8 位 + 到期日 + 删除按钮。 */
@Composable
private fun ClientCertificateRow(
    certificate: ClientCertificate,
    onDelete: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = certificateDisplayLabel(certificate.label, certificate.subject)
        ?: stringResource(R.string.gateway_cert_label_fallback)
    val deleteDescription = stringResource(R.string.gateway_cert_delete_desc, label)
    val serial = formatCertificateSerial(certificate.serial)
        .ifEmpty { stringResource(R.string.gateway_cert_serial_unknown) }
    val expiry = formatCertificateExpiry(certificate.notAfter)
    val state = certificateState(certificate.notAfter)
    val stateLabel = when (state) {
        ClientCertificateState.EXPIRED -> stringResource(R.string.gateway_cert_state_expired)
        ClientCertificateState.EXPIRING_SOON -> stringResource(R.string.gateway_cert_state_expiring)
        ClientCertificateState.VALID -> null
    }
    val stateColor = if (state == ClientCertificateState.EXPIRED) {
        colorScheme.error
    } else {
        Color(0xFFFF9F0A)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gateway_cert_serial_expiry, serial, expiry),
                color = colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            stateLabel?.let { text ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    color = stateColor,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.gateway_cert_delete),
            color = colorScheme.error,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = deleteDescription }
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

/** 密码输入对话框：密文输入，至少 6 位才允许导入。 */
@Composable
private fun CertificatePasswordDialog(
    importing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var password by remember { mutableStateOf("") }
    val valid = isValidCertificatePassword(password)

    Dialog(onDismissRequest = { if (!importing) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colorScheme.surface,
            contentColor = colorScheme.onSurface,
            shadowElevation = 24.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = stringResource(R.string.gateway_cert_password_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.gateway_cert_password_body),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.gateway_cert_password_label), fontSize = 12.sp)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = password.isNotEmpty() && !valid,
                    supportingText = if (password.isNotEmpty() && !valid) {
                        { Text(stringResource(R.string.gateway_cert_password_too_short)) }
                    } else {
                        null
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !importing) {
                        Text(stringResource(R.string.gateway_cert_cancel))
                    }
                    TextButton(
                        onClick = { onConfirm(password) },
                        enabled = valid && !importing,
                    ) {
                        Text(
                            if (importing) {
                                stringResource(R.string.gateway_cert_import_working)
                            } else {
                                stringResource(R.string.gateway_cert_import_confirm)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 删除确认：避免误删后公网直接连不上。 */
@Composable
private fun CertificateDeleteDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colorScheme.surface,
            contentColor = colorScheme.onSurface,
            shadowElevation = 24.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = stringResource(R.string.gateway_cert_delete_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.gateway_cert_delete_body, label),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.gateway_cert_cancel))
                    }
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error),
                    ) {
                        Text(stringResource(R.string.gateway_cert_delete_confirm))
                    }
                }
            }
        }
    }
}
