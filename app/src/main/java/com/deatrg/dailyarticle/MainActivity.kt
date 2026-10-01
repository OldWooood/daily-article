package com.deatrg.dailyarticle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统自带 HTTP 响应缓存：带 Cache-Control 的源（古诗文 max-age=7200 等）
        // 重复点「随机一篇」时直接读缓存，少开 radio，省流量也省电。
        runCatching {
            val cacheDir = java.io.File(cacheDir, "http")
            if (android.net.http.HttpResponseCache.getInstalled() == null) {
                android.net.http.HttpResponseCache.install(cacheDir, 10L * 1024 * 1024)
            }
        }
        enableEdgeToEdge()
        setContent { DailyAppTheme { DailyScreen() } }
    }
}

@Composable
fun DailyAppTheme(content: @Composable () -> Unit) {
    // 主题跟随系统：Android 12+ 用动态取色，否则纯深浅色
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // 全程使用 MaterialTheme.typography（单位 sp），不覆盖 fontScale，文字大小自动跟随系统
    MaterialTheme(colorScheme = scheme) { content() }
}
