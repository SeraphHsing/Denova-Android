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
import androidx.compose.ui.res.stringResource
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.i18n.resolve
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
    error: UiText?,
    onDismiss: () -> Unit,
    onCreate: (title: String, author: String, description: String, fromIdea: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val canSubmit = title.isNotBlank() && !creating

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text(stringResource(R.string.create_book_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.create_book_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.create_book_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.create_book_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.create_book_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, author, description, true) },
                enabled = canSubmit,
            ) { Text(if (creating) stringResource(R.string.create_book_creating) else stringResource(R.string.create_book_from_idea)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { onCreate(title, author, description, false) },
                    enabled = canSubmit,
                ) { Text(stringResource(R.string.create_book_only)) }
                TextButton(onClick = onDismiss, enabled = !creating) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
