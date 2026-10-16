package net.wastu.memoize.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.wastu.memoize.R
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteRepository
import java.io.File
import java.net.URLEncoder
import java.util.Locale

fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
}

fun attachmentImageModel(
    attachment: NoteAttachment,
    repository: NoteRepository,
    server: String?,
    thumbnail: Boolean = true
): Any? {
    attachment.localPath?.let { path ->
        val file = File(path)
        if (file.isFile && file.length() > 0) return file
    }
    val original = repository.originalAttachmentFile(attachment.id)
    if (original.isFile && original.length() > 0) return original
    val cached = repository.cachedAttachmentFile(attachment.id)
    if (cached.isFile && cached.length() > 0) return cached
    if (server.isNullOrBlank()) return null
    val uid = (attachment.remoteName ?: attachment.id).removePrefix("attachments/")
    val base = server.trimEnd('/')
    val filename = try {
        URLEncoder.encode(attachment.filename, "UTF-8")
    } catch (_: Exception) {
        attachment.filename
    }
    return "$base/file/attachments/$uid/$filename${if (thumbnail) "?thumbnail=true" else ""}"
}

@Composable
fun NoteMediaGrid(
    attachments: List<NoteAttachment>,
    repository: NoteRepository,
    server: String?,
    modifier: Modifier = Modifier,
    onImageClick: ((NoteAttachment) -> Unit)? = null,
    onFileClick: ((NoteAttachment) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.isImage }
    val files = attachments.filter { !it.isImage }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty()) {
            when (images.size) {
                1 -> {
                    val img = images[0]
                    val model = attachmentImageModel(img, repository, server, thumbnail = true)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(enabled = onImageClick != null) { onImageClick?.invoke(img) }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(model)
                                .crossfade(true)
                                .build(),
                            contentDescription = img.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
                        )
                    }
                }
                2 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (img in images) {
                            val model = attachmentImageModel(img, repository, server, thumbnail = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable(enabled = onImageClick != null) { onImageClick?.invoke(img) }
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(model)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = img.filename,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
                3 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val first = images[0]
                        val firstModel = attachmentImageModel(first, repository, server, thumbnail = true)
                        Box(
                            modifier = Modifier
                                .weight(1.2f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(enabled = onImageClick != null) { onImageClick?.invoke(first) }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(firstModel)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = first.filename,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (img in images.drop(1)) {
                                val model = attachmentImageModel(img, repository, server, thumbnail = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable(enabled = onImageClick != null) { onImageClick?.invoke(img) }
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(model)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = img.filename,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    // 4 or more
                    val displayList = images.take(4)
                    val remaining = images.size - 4
                    Column(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (img in displayList.take(2)) {
                                val model = attachmentImageModel(img, repository, server, thumbnail = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable(enabled = onImageClick != null) { onImageClick?.invoke(img) }
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(model)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = img.filename,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val third = displayList[2]
                            val thirdModel = attachmentImageModel(third, repository, server, thumbnail = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable(enabled = onImageClick != null) { onImageClick?.invoke(third) }
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(thirdModel)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = third.filename,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            val fourth = displayList[3]
                            val fourthModel = attachmentImageModel(fourth, repository, server, thumbnail = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable(enabled = onImageClick != null) { onImageClick?.invoke(fourth) }
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(fourthModel)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = fourth.filename,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (remaining > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "+$remaining",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (files.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (file in files) {
                    AssistChip(
                        onClick = { onFileClick?.invoke(file) },
                        label = {
                            Text(
                                text = file.filename,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_file),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            Text(
                                text = formatFileSize(file.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun MediaLightboxDialog(
    attachment: NoteAttachment?,
    repository: NoteRepository,
    server: String?,
    onDismiss: () -> Unit,
) {
    if (attachment == null) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.95f)
        ) {
            Box(Modifier.fillMaxSize()) {
                val fullModel = attachmentImageModel(attachment, repository, server, thumbnail = false)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(fullModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = attachment.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )

                // Top controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Close preview")
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = attachment.filename,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatFileSize(attachment.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditorAttachmentStrip(
    attachments: List<NoteAttachment>,
    repository: NoteRepository,
    server: String?,
    busy: Boolean,
    onOpen: (NoteAttachment) -> Unit,
    onRemove: (NoteAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(attachments, key = { it.id }) { attachment ->
            if (attachment.isImage) {
                val model = attachmentImageModel(attachment, repository, server, thumbnail = true)
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(enabled = !busy) { onOpen(attachment) }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(model)
                            .crossfade(true)
                            .build(),
                        contentDescription = attachment.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .clickable(enabled = !busy) { onRemove(attachment) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Remove ${attachment.filename}",
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(64.dp).width(160.dp).clickable(enabled = !busy) { onOpen(attachment) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = attachment.filename,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatFileSize(attachment.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable(enabled = !busy) { onRemove(attachment) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Remove ${attachment.filename}",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
