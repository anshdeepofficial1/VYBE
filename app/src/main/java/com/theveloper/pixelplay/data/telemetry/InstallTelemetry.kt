package com.theveloper.pixelplay.data.telemetry

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import com.theveloper.pixelplay.BuildConfig
import com.google.android.gms.appset.AppSet
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Optional, anonymous install/active-use counter. No email, music history or raw device ID leaves the phone. */
object InstallTelemetry {
    private const val PREFS = "vybe_install_telemetry"
    private const val KEY_CONSENT = "consent"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_INSTALLATION_HASH = "installation_hash"
    private const val ENDPOINT = "https://vybetune.vercel.app/api/install"
    private const val DAY_MS = 86_400_000L
    private const val OFFICIAL_RELEASE_CERT_SHA256 = "e28e1736116244bdd3bf2e3e4faa0bc11c7ba9fb7338e62f7c9540f963f3f084"
    private val client = OkHttpClient()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, true)

    fun setConsent(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousHash = prefs.getString(KEY_INSTALLATION_HASH, null)
        prefs.edit().putBoolean(KEY_CONSENT, enabled).apply()
        if (!enabled && !previousHash.isNullOrBlank()) {
            backgroundScope.launch {
                runCatching {
                    val body = JSONObject().put("installation", previousHash).toString()
                        .toRequestBody("application/json".toMediaType())
                    client.newCall(Request.Builder().url(ENDPOINT).delete(body).build()).execute().close()
                }
                prefs.edit().remove(KEY_INSTALLATION_HASH).remove(KEY_LAST_SENT).apply()
            }
        }
    }

    suspend fun recordIfDue(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!hasConsent(context)) return@withContext false
        // Debug, re-signed and modified APKs must never inflate the public install count.
        if (!hasOfficialReleaseSignature(context)) return@withContext false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_SENT, 0L) < DAY_MS) return@withContext true
        val rawId = runCatching { AppSet.getClient(context).appSetIdInfo.await().id }
            .getOrNull()?.takeIf(String::isNotBlank)
            ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty().ifBlank { return@withContext false }
        val installationHash = MessageDigest.getInstance("SHA-256")
            .digest("${context.packageName}:$rawId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = JSONObject().apply {
            put("installation", installationHash)
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("sdk", Build.VERSION.SDK_INT)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        }.toString().toRequestBody("application/json".toMediaType())
        runCatching {
            client.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) return@use false
                prefs.edit()
                    .putString(KEY_INSTALLATION_HASH, installationHash)
                    .putLong(KEY_LAST_SENT, now)
                    .apply()
                true
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun hasOfficialReleaseSignature(context: Context): Boolean = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            packageInfo.signatures?.map { it.toByteArray() }.orEmpty()
        }
        certificates.any { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate)
                .joinToString("") { "%02x".format(it) }
                .equals(OFFICIAL_RELEASE_CERT_SHA256, ignoreCase = true)
        }
    }.getOrDefault(false)
}
