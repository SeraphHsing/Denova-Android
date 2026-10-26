package com.denova.novara.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import com.denova.novara.core.onboarding.OnboardingLogic

/**
 * 建书对话框（WRI-001）：只提交基线支持的三个字段。
 *
 * 「从想法开始」= 建书成功后把 [OnboardingLogic.INIT_PROMPT] 填进创作 Agent 的输入框，
 * **不自动发送**（基线明确不自动消耗模型调用）。
 */
@Composable
fun CreateBookDialog(
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, author: String, description: String, fromIdea: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val canSubmit = title.isNotBlank() && !creating

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("从想法创建书籍") },
        text = {
            Column {
                Text(
                    "先建一本书作为工作区，再和创作 Agent 一起梳理灵感。书名会决定工作目录名，同名目录已存在会被服务端拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("简介（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, author, description, true) },
                enabled = canSubmit,
            ) { Text(if (creating) "创建中…" else "从想法开始") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { onCreate(title, author, description, false) },
                    enabled = canSubmit,
                ) { Text("只建书") }
                TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") }
            }
        },
    )
}
