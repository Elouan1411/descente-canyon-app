package fr.descentecanyon.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignatureUtils {

    private const val APP_SECRET = "descente_canyon_secret_key_2026"

    fun getApkSignatureSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(signatures[0].toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            } else {
                "unsigned_dev_build"
            }
        } catch (e: Exception) {
            "error_retrieving_signature"
        }
    }

    fun generateHmacAuthHeader(context: Context, path: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val apkHash = getApkSignatureSha256(context)

        val payload = "$timestamp:$nonce:$apkHash:$path"

        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(APP_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)

        val hashBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val signatureHex = hashBytes.joinToString("") { "%02x".format(it) }

        return "$timestamp:$nonce:$signatureHex"
    }
}
