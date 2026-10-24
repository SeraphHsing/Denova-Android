package com.denova.novara.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.denova.novara.core.network.DenovaClient

/**
 * 受保护图片的加载器（G1）。
 *
 * 复用 [DenovaClient.okHttpClient]（携带该服务器会话的 Cookie），因此
 * `GET /api/projects/:id/files/asset?path=` 这类需要认证的资源可以直接交给 Coil 加载，
 * 不需要另外注入 token。
 *
 * 限定：只覆盖 http（当前测试与内网部署均为明文）；https 未验证，
 * 也不在此处放宽 TLS 校验（见 AGENTS §5）。
 */
fun imageLoaderFor(context: Context, client: DenovaClient): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client.okHttpClient }))
        }
        .build()
