package com.galigalimusic.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（C11 云端备份用）。
 *
 * 只实现备份所需的三个动作：建目录（MKCOL）、上传（PUT）、下载（GET）。
 * 走 Basic 认证，适配 Nextcloud / 坚果云 / Alist 等标准 WebDAV 服务。
 */
object WebDavClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val JSON = "application/json; charset=utf-8"

    /** 备份在远端使用的固定文件名（每次覆盖，始终是最新一份） */
    const val BACKUP_FILE = "galigali-playlists-latest.json"

    private fun auth(user: String, pass: String) = Credentials.basic(user, pass)

    /** 测试连通性：PROPFIND 根目录，返回服务器标识或错误 */
    suspend fun test(baseUrl: String, user: String, pass: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/")
                    .header("Authorization", auth(user, pass))
                    .method("PROPFIND", null)
                    .header("Depth", "0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 207 -> "连接正常"
                        401, 403 -> "认证失败，请检查账号密码"
                        else -> "服务器返回 ${resp.code}"
                    }.let { msg ->
                        if (resp.code == 401 || resp.code == 403 || resp.code >= 400) {
                            throw IllegalStateException(msg)
                        }
                        msg
                    }
                }
            }
        }

    /**
     * 上传备份内容到 `<baseUrl>/galigali-backup/<fileName>`。
     * 目录不存在时先 MKCOL 建一层（已存在返回 405 也算成功）。
     */
    suspend fun upload(
        baseUrl: String,
        user: String,
        pass: String,
        fileName: String,
        content: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = baseUrl.trimEnd('/')
            val dir = "$root/galigali-backup"
            // 建目录（409/405 都视为可接受：已存在或服务端不允许 MKCOL 时 PUT 自愈）
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("$dir/")
                        .header("Authorization", auth(user, pass))
                        .method("MKCOL", null)
                        .build()
                ).execute().close()
            }
            val body = content.toRequestBody(JSON.toMediaType())
            val resp = client.newCall(
                Request.Builder()
                    .url("$dir/$fileName")
                    .header("Authorization", auth(user, pass))
                    .put(body)
                    .build()
            ).execute()
            resp.use {
                if (it.code !in 200..299) {
                    throw IllegalStateException("上传失败（${it.code}）")
                }
            }
        }
    }

    /** 下载备份内容（文件不存在返回失败，由调用方提示） */
    suspend fun download(
        baseUrl: String,
        user: String,
        pass: String,
        fileName: String = BACKUP_FILE,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/galigali-backup/$fileName"
            val resp = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", auth(user, pass))
                    .get()
                    .build()
            ).execute()
            resp.use {
                if (it.code == 404) throw IllegalStateException("云端还没有备份")
                if (it.code !in 200..299) throw IllegalStateException("下载失败（${it.code}）")
                it.body?.string().orEmpty().ifEmpty { throw IllegalStateException("备份内容为空") }
            }
        }
    }
}
