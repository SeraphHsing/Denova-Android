package com.denova.novara.feature.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.i18n.resolve

/** 默认服务器地址（隔离测试服务器；可在输入框里改）。 */
private const val DEFAULT_SERVER = "192.168.2.102:18081"

/**
 * 连接与认证页四态（AUTH-001 / AUTH-004 / AUTH-007）：
 * - Empty：地址输入 + 连接；
 * - Checking：**保留地址输入与重试入口**（Web 在 pending 时是全屏 loading，但原生需要可并发的入口，
 *   否则构造不出「旧 401 与新登录交错」，也无法在超时后原地重试）；
 * - Failed：错误块（标题 + 服务端消息）+ 重试，地址与已输入内容不丢；
 * - Unauthenticated：登录 / 配对表单，提交中禁用并显示进行中文案。
 */
@Composable
fun ConnectionScreen(viewModel: ConnectionViewModel) {
    val state by viewModel.state.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val savedServer by viewModel.savedServer.collectAsState()
    val submitting by viewModel.submitting.collectAsState()

    // 地址提升到这里：连接中/失败重试时不能因为分支切换把用户输入重置掉（AUTH-007）。
    var server by remember { mutableStateOf(DEFAULT_SERVER) }
    var serverEdited by remember { mutableStateOf(false) }
    LaunchedEffect(savedServer) {
        val saved = savedServer
        if (!serverEdited && !saved.isNullOrBlank()) server = saved
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Novara", style = MaterialTheme.typography.headlineLarge)

        when (val s = state) {
            is ConnectionUiState.Empty -> ConnectForm(
                value = server,
                onChange = {
                    serverEdited = true
                    server = it
                },
                onConnect = { viewModel.connect(server) },
                actionError = actionError,
            )

            is ConnectionUiState.Checking -> {
                ConnectForm(
                    value = server,
                    onChange = {
                        serverEdited = true
                        server = it
                    },
                    onConnect = { viewModel.connect(server) },
                    actionError = actionError,
                )
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.conn_connecting, s.server.origin))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.conn_retry))
                }
            }

            is ConnectionUiState.Failed -> {
                ConnectForm(
                    value = server,
                    onChange = {
                        serverEdited = true
                        server = it
                    },
                    onConnect = { viewModel.connect(server) },
                    actionError = actionError,
                )
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.conn_connection_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(s.message.resolve(), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.conn_retry))
                }
            }

            is ConnectionUiState.Unauthenticated -> {
                Text(
                    stringResource(R.string.conn_login_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.conn_login_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.conn_server, s.server.origin), style = MaterialTheme.typography.bodyMedium)
                if (s.local) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.conn_local), color = MaterialTheme.colorScheme.primary)
                }
                // AUTH-006：登录页也要能换服务器，否则连到 B 只能靠杀进程重来。
                TextButton(
                    onClick = {
                        serverEdited = false
                        viewModel.changeServer()
                    },
                ) {
                    Text(stringResource(R.string.conn_change_server))
                }
                AuthForms(viewModel, actionError, submitting)
            }

            // 已认证态由 MainActivity 路由到编辑器，此分支不可达。
            is ConnectionUiState.Authenticated -> Unit
        }
    }
}

@Composable
private fun ConnectForm(
    value: String,
    onChange: (String) -> Unit,
    onConnect: () -> Unit,
    actionError: UiText?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.conn_server_address)) },
        placeholder = { Text(stringResource(R.string.conn_server_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.conn_connect))
    }
    actionError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it.resolve(), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun AuthForms(viewModel: ConnectionViewModel, actionError: UiText?, submitting: Boolean) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(R.string.conn_username)) },
        singleLine = true,
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.conn_password)) },
        singleLine = true,
        enabled = !submitting,
        visualTransformation = PasswordVisualTransformation(),
        // 密码字段必须声明为 Password 类型并关闭自动纠错/联想：
        // 否则输入法会把密码记进建议词与剪贴板历史（真机验证时已观察到密码出现在候选栏）。
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    // 对齐 Web 登录卡片的 rememberHint（会话保留 30 天，可在设置退出）。
    Text(
        stringResource(R.string.conn_remember_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.login(username, password) },
        enabled = !submitting && username.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (submitting) R.string.conn_signing_in else R.string.conn_login))
    }

    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.conn_pair_token)) },
        singleLine = true,
        enabled = !submitting,
        // 配对 token 是凭证，同样不能进输入法词库
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.pair(token) },
        enabled = !submitting && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (submitting) R.string.conn_pairing else R.string.conn_pair))
    }

    actionError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it.resolve(), color = MaterialTheme.colorScheme.error)
    }
}
