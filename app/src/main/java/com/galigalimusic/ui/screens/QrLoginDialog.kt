package com.galigalimusic.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.galigalimusic.data.remote.BilibiliApi
import kotlinx.coroutines.delay

/**
 * 扫码登录对话框：展示 B 站登录二维码，2s 轮询状态；
 * 过期自动重取；成功后 Cookie 已由 BiliSession.jar 落盘，回调 onLoggedIn。
 */
@Composable
fun QrLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在获取二维码…") }
    var session by remember { mutableStateOf<BilibiliApi.QrLogin?>(null) }

    suspend fun regenerate() {
        statusText = "正在获取二维码…"
        val qr = BilibiliApi.qrGenerate()
        session = qr
        // 便于 PC 端渲染同一二维码辅助扫码验证
        android.util.Log.i("GaligaliLogin", "qr content: ${qr.qrContent}")
        qrBitmap = qrToBitmap(qr.qrContent)
        statusText = "打开 B 站 App 扫一扫"
    }

    LaunchedEffect(Unit) {
        regenerate()
        while (true) {
            val s = session ?: break
            when (BilibiliApi.qrPoll(s.qrcodeKey)) {
                BilibiliApi.QrState.CONFIRMED -> {
                    statusText = "登录成功 ✓"
                    onLoggedIn()
                    break
                }
                BilibiliApi.QrState.SCANNED -> statusText = "已扫描，请在手机上确认"
                BilibiliApi.QrState.EXPIRED -> regenerate()
                BilibiliApi.QrState.WAITING -> Unit
            }
            delay(2000)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("扫码登录 B 站", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.White, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = qrBitmap
                    if (bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(200.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/** ZXing 生成二维码位图（白底黑码，静区 1 模块） */
private fun qrToBitmap(content: String): Bitmap {
    val size = 512
    val matrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}
