package com.denova.novara.feature.books

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.denova.novara.R
import com.denova.novara.core.export.DownloadsSaver
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.image.imageLoaderFor
import com.denova.novara.core.network.BookRecord
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍管理（P4-C / BOK-001·002·003），对齐基线 `components/Home/HomeView.tsx`：
 * 当前书籍 + 书架列表（封面 / 书名 / 作者 / 路径）、新建 / 导入小说 / 导入角色卡、
 * 导出 TXT、编辑信息、上传封面、排序模式、从书架移除。
 */
@Composable
fun BooksScreen(
    client: DenovaClient?,
    currentProjectId: String,
    onOpenDrawer: () -> Unit,
    /** 当前书籍变化（新建 / 切换 / 导入 / 移除）后回传给外壳，保持项目上下文一致。 */
    onCurrentBookChanged: (BookRecord?) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as Application
    val factory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { BooksViewModel(application) } }
    }
    val viewModel: BooksViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = remember(client) { client?.let { imageLoaderFor(context, it) } }

    LaunchedEffect(client, currentProjectId) { client?.let { viewModel.bindClient(it, currentProjectId) } }

    // 当前书籍变化后回传外壳，保持抽屉、编辑器与项目目录的项目上下文一致。
    LaunchedEffect(state.currentProjectId, state.books) {
        onCurrentBookChanged(state.currentBook())
    }

    var createOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<BookRecord?>(null) }
    var removeTarget by remember { mutableStateOf<BookRecord?>(null) }
    var coverTarget by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    val novelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                readUpload(context, it, "text/plain")?.let { file -> viewModel.previewNovel(file) }
            }
        }
    }
    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                readUpload(context, it, "application/octet-stream")?.let { file -> viewModel.previewCard(file) }
            }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val path = coverTarget
        if (uri != null && path != null) {
            scope.launch {
                readUpload(context, uri, "image/*")?.let { file -> viewModel.uploadCover(path, file) }
            }
        }
        coverTarget = null
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_bookCount, state.books.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { sortMenu = true }) { Text("⇅") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_sortLink_manual)) },
                    onClick = {
                        sortMenu = false
                        viewModel.setSortMode("manual")
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_sortLink_recent)) },
                    onClick = {
                        sortMenu = false
                        viewModel.setSortMode("recent")
                    },
                )
            }
            TextButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.refresh)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { createOpen = true }) { Text(stringResource(R.string.home_createBook)) }
            TextButton(onClick = { novelPicker.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream")) }) {
                Text(stringResource(R.string.home_importNovel))
            }
            TextButton(onClick = { cardPicker.launch(arrayOf("application/json", "image/png", "application/octet-stream")) }) {
                Text(stringResource(R.string.home_importCard))
            }
        }
        state.progress?.let {
            Text(
                it.resolve(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        state.notice?.let {
            Text(
                it.resolve(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        state.error?.let {
            Text(
                it.resolve(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        HorizontalDivider()

        if (state.books.isEmpty() && !state.loading) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.home_emptyDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            val current = state.currentBook()
            item("current") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.home_currentBook).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(
                            client = client,
                            book = current,
                            imageLoader = imageLoader,
                            modifier = Modifier.width(48.dp).aspectRatio(3f / 4f),
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                current?.name?.ifBlank { stringResource(R.string.home_unnamedBook) }
                                    ?: stringResource(R.string.home_currentWorkspaceUnset),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                current?.path ?: stringResource(R.string.home_startHint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (current != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                enabled = state.progress == null,
                                onClick = {
                                    scope.launch {
                                        val file = viewModel.export(current.path) ?: return@launch
                                        when (val result = DownloadsSaver.save(
                                            context = context,
                                            fileName = file.fileName,
                                            bytes = file.bytes,
                                            mimeType = "text/plain",
                                        )) {
                                            is DownloadsSaver.Result.Saved -> viewModel.load(force = false)
                                            is DownloadsSaver.Result.Failed -> Unit
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.home_exportTxt)) }
                            TextButton(onClick = {
                                coverTarget = current.path
                                coverPicker.launch(arrayOf("image/*"))
                            }) { Text(stringResource(R.string.home_uploadCover)) }
                        }
                    }
                }
                HorizontalDivider()
            }
            item("shelf") {
                Text(
                    stringResource(R.string.home_bookshelf),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(state.books, key = { it.path }) { book ->
                val currentBook = book.projectId == state.currentProjectId
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(
                        client = client,
                        book = book,
                        imageLoader = imageLoader,
                        modifier = Modifier.width(48.dp).aspectRatio(3f / 4f),
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            book.name.ifBlank { stringResource(R.string.home_unnamedBook) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            book.author.ifBlank { stringResource(R.string.home_authorUnset) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            book.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (currentBook) {
                        Text(
                            stringResource(R.string.books_current_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    TextButton(onClick = { viewModel.switchTo(book) }, enabled = !currentBook) {
                        Text(stringResource(R.string.books_switch))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 70.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { editTarget = book }) { Text(stringResource(R.string.home_editInfo)) }
                    if (state.sortMode == "manual") {
                        TextButton(onClick = { viewModel.move(book, -1) }) { Text("↑") }
                        TextButton(onClick = { viewModel.move(book, 1) }) { Text("↓") }
                    }
                    TextButton(onClick = {
                        coverTarget = book.path
                        coverPicker.launch(arrayOf("image/*"))
                    }) { Text(stringResource(R.string.home_cover)) }
                    TextButton(onClick = { removeTarget = book }) {
                        Text(stringResource(R.string.home_softDeleteBook))
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (createOpen) {
        BookFormDialog(
            titleText = stringResource(R.string.home_createBook),
            initial = null,
            onDismiss = { createOpen = false },
            onConfirm = { title, author, description ->
                createOpen = false
                viewModel.create(title, author, description)
            },
        )
    }

    editTarget?.let { book ->
        var meta by remember(book.path) { mutableStateOf<Triple<String, String, String>?>(null) }
        LaunchedEffect(book.path) {
            viewModel.info(book.path) { m ->
                meta = Triple(m.title.ifBlank { book.name }, m.author, m.description)
            }
        }
        val values = meta
        if (values != null) {
            BookFormDialog(
                titleText = stringResource(R.string.home_editInfo),
                initial = values,
                authorPlaceholder = stringResource(R.string.home_authorEditPlaceholder),
                onDismiss = { editTarget = null },
                onConfirm = { title, author, description ->
                    editTarget = null
                    viewModel.updateInfo(book.path, title, author, description)
                },
            )
        }
    }

    removeTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.home_deleteBook)) },
            text = { Text(stringResource(R.string.home_deleteBookDescription, book.name)) },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    viewModel.remove(book)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    state.novelPreview?.let { preview ->
        NovelImportDialog(
            preview = preview,
            busy = state.busy,
            onDismiss = { viewModel.dismissNovelPreview() },
            onConfirm = { title, author, description ->
                viewModel.confirmNovelImport(title, author, description)
            },
        )
    }

    state.cardPreview?.let { preview ->
        CardImportDialog(
            preview = preview,
            busy = state.busy,
            onDismiss = { viewModel.dismissCardPreview() },
            onConfirm = { title -> viewModel.confirmCardImport(title) },
        )
    }
}

@Composable
private fun Cover(
    client: DenovaClient?,
    book: BookRecord?,
    imageLoader: coil3.ImageLoader?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        if (client != null && book != null && imageLoader != null && book.coverUpdatedAt != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(client.bookCoverUrl(book.path, book.coverUpdatedAt).toString())
                    .build(),
                imageLoader = imageLoader,
                contentDescription = book.name,
                contentScale = ContentScale.Crop,
                onError = { state ->
                    com.denova.novara.core.logging.DenovaLog.w(
                        "Books",
                        "cover load failed path=${book.path} err=${state.result.throwable.message}",
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("📖", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BookFormDialog(
    titleText: String,
    initial: Triple<String, String, String>?,
    authorPlaceholder: String = stringResource(R.string.home_authorPlaceholder),
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, description: String) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.first.orEmpty()) }
    var author by remember { mutableStateOf(initial?.second.orEmpty()) }
    var description by remember { mutableStateOf(initial?.third.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.home_bookTitlePlaceholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(authorPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.home_descriptionPlaceholder)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title, author, description) },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun NovelImportDialog(
    preview: com.denova.novara.core.network.NovelImportPreview,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, description: String) -> Unit,
) {
    var title by remember { mutableStateOf(preview.title) }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_importNovel)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.novel_import_summary,
                        preview.chapterCount,
                        preview.totalChars,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                preview.warnings.take(3).forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.home_bookTitlePlaceholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.home_authorPlaceholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.home_descriptionPlaceholder)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank(), onClick = { onConfirm(title, author, description) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CardImportDialog(
    preview: com.denova.novara.core.network.CharacterCardPreview,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String) -> Unit,
) {
    var title by remember { mutableStateOf(preview.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_importCard)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.card_import_summary, preview.entryCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (preview.tags.isNotEmpty()) {
                    Text(
                        preview.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                preview.compatibility.warnings.take(2).forEach {
                    Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.home_bookTitlePlaceholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank(), onClick = { onConfirm(title) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 从 content:// 读出要上传的字节（文件名取自 OpenableColumns，供服务端判类型）。 */
private suspend fun readUpload(context: Context, uri: Uri, fallbackMime: String): UploadFile? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.isEmpty()) return@runCatching null
            UploadFile(
                fileName = name.ifBlank { "upload.bin" },
                mimeType = resolver.getType(uri) ?: fallbackMime,
                bytes = bytes,
            )
        }.getOrNull()
    }
