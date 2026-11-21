package com.musicwall.player.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ServerProfile(val address: HttpUrl, val username: String, val password: String) {
    override fun toString(): String = "ServerProfile(${address.host})"

    companion object {
        fun validate(address: String, username: String, password: String): ServerProfile {
            val url = parseServerAddress(address)
            require(username.isNotBlank()) { "请输入 Navidrome 用户名。" }
            require(password.isNotEmpty()) { "请输入 Navidrome 密码。" }
            return ServerProfile(url, username.trim(), password)
        }
    }
}

internal fun parseServerAddress(address: String): HttpUrl {
    val url = address.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("请输入完整地址，例如 http://192.168.1.20:4533。")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "地址可以包含部署路径，请把用户名和密码填在下方。"
    }
    require(url.isHttps || isPrivateHost(url.host)) { "公网地址请使用 HTTPS，局域网 HTTP 请填写 NAS 的内网 IP。" }
    return url.newBuilder().apply {
        if (!url.encodedPath.endsWith('/')) addPathSegment("")
    }.build()
}

private fun isPrivateHost(host: String): Boolean {
    if (host == "localhost" || host == "::1") return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val numbers = parts.map { part ->
        val number = part.toIntOrNull() ?: return false
        if (number !in 0..255 || part != number.toString()) return false
        number
    }
    return numbers[0] == 10 || numbers[0] == 127 ||
        (numbers[0] == 192 && numbers[1] == 168) ||
        (numbers[0] == 172 && numbers[1] in 16..31) ||
        (numbers[0] == 169 && numbers[1] == 254)
}

class ServerStore(context: Context) {
    private val preferences = context.getSharedPreferences("navidrome", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): ServerProfile? {
        val encrypted = preferences.getString("profile", null) ?: return null
        return runCatching {
            val (iv, content) = encrypted.split('.', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val item = JSONObject(String(cipher.doFinal(content), Charsets.UTF_8))
            ServerProfile.validate(item.getString("address"), item.getString("username"), item.getString("password"))
        }.getOrNull()
    }

    @Synchronized
    fun save(profile: ServerProfile) {
        val item = JSONObject()
            .put("address", profile.address.toString())
            .put("username", profile.username)
            .put("password", profile.password)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(item.toString().toByteArray(Charsets.UTF_8))
        val encoded = listOf(cipher.iv, data).joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        check(preferences.edit().putString("profile", encoded).commit()) { "无法保存连接配置。" }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove("profile").commit()) { "无法移除连接配置。" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "musicwall-navidrome"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
