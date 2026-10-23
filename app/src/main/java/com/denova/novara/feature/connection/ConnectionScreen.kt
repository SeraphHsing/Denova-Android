package com.denova.novara.feature.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionScreen(viewModel: ConnectionViewModel) {
    val state by viewModel.state.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val savedServer by viewModel.savedServer.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Novara", style = MaterialTheme.typography.headlineLarge)

        when (val s = state) {
            is ConnectionUiState.Empty -> ServerInput(viewModel, actionError, savedServer)
            is ConnectionUiState.Failed -> {
                ServerInput(viewModel, actionError, savedServer)
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is ConnectionUiState.Checking -> {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在连接 ${s.server.origin}…")
            }
            is ConnectionUiState.Unauthenticated -> {
                Spacer(Modifier.height(8.dp))
                Text("服务器：${s.server.origin}", style = MaterialTheme.typography.bodyMedium)
                if (s.local) {
                    Spacer(Modifier.height(4.dp))
                    Text("（本机连接）", color = MaterialTheme.colorScheme.primary)
                }
                AuthForms(viewModel, actionError)
            }
            is ConnectionUiState.Authenticated -> {
                Spacer(Modifier.height(24.dp))
                Text("已连接到 ${s.server.origin}", style = MaterialTheme.typography.titleMedium)
                if (s.local) {
                    Text("本机身份", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.logout() }) {
                    Text("退出登录")
                }
            }
        }
    }
}

@Composable
private fun ServerInput(viewModel: ConnectionViewModel, actionError: String?, initialServer: String?) {
    var server by remember(initialServer) { mutableStateOf(initialServer ?: "192.168.2.102:18081") }
    OutlinedTextField(
        value = server,
        onValueChange = { server = it },
        label = { Text("服务器地址") },
        placeholder = { Text("host:port 或 http(s)://host:port") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.connect(server) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("连接")
    }
    actionError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun AuthForms(viewModel: ConnectionViewModel, actionError: String?) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("用户名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("密码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.login(username, password) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("登录")
    }

    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("配对链接 token") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.pair(token) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("配对")
    }

    actionError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}
