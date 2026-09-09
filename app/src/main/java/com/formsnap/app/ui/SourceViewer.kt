package com.formsnap.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.domain.model.SourceRegion
import kotlinx.coroutines.CancellationException

private sealed interface ImageState {
    data object Loading : ImageState
    data object Error : ImageState
    data class Ready(val bitmap: Bitmap) : ImageState
}

@Composable
fun SourceImagePane(source: SourceDocument?, region: SourceRegion?, loader: SourceImageLoader, modifier: Modifier = Modifier) {
    val state by produceState<ImageState>(ImageState.Loading, source?.sourceUri, region) {
        value = ImageState.Loading
        value = try {
            if (source == null) ImageState.Error else {
                ImageState.Ready(loader.load(source.sourceUri, 1600, region))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ImageState.Error }
    }
    when (val current = state) {
        ImageState.Loading -> Text("正在读取原纸…", modifier.padding(16.dp))
        ImageState.Error -> Text("原图暂不可用，请返回来源页面重新检查。", modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        is ImageState.Ready -> Image(current.bitmap.asImageBitmap(),
            contentDescription = if (region == null) "原始页面" else "当前字段对应的原纸区域",
            contentScale = ContentScale.Fit, modifier = modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceViewer(source: SourceDocument?, loader: SourceImageLoader, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("原始页面") }, navigationIcon = { TextButton(onClick = onClose) { Text("关闭") } },
                actions = { TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("复位") } })
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                Text("双指缩放、拖动查看。图片仍由原存储位置管理。", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                SourceImagePane(source, null, loader, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y; clip = true
                }.transformable(transform))
            }
        }
    }
}
