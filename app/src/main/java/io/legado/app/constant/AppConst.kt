package io.legado.app.constant

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.Keep
import cn.hutool.crypto.digest.DigestUtil
import io.legado.app.BuildConfig
import io.legado.app.help.update.AppVariant
import org.apache.commons.lang3.time.FastDateFormat
import splitties.init.appCtx

private const val BETA_SIGNATURE =
    "E2400519DF26F329EFC3FA1288DB46E8A23C6AEEB14B5378AD80CA9F8136C146"

@Suppress("ConstPropertyName")
@SuppressLint("SimpleDateFormat")
object AppConst {

    const val APP_TAG = "Legado"

    const val channelIdDownload = "channel_download"
    const val channelIdReadAloud = "channel_read_aloud"
    const val channelIdWeb = "channel_web"

    const val UA_NAME = "User-Agent"

    const val MAX_THREAD = 9

    const val DEFAULT_WEBDAV_ID = -1L

    val timeFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("HH:mm")
    }

    val dateFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yyyy/MM/dd HH:mm")
    }

    val fileNameFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yy-MM-dd-HH-mm-ss")
    }

    const val imagePathKey = "imagePath"

    val menuViewNames = arrayOf(
        "com.android.internal.view.menu.ListMenuItemView",
        "androidx.appcompat.view.menu.ListMenuItemView"
    )

    @SuppressLint("PrivateResource")
    val sysElevation = appCtx.resources
        .getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
        .toInt()

    val androidId: String by lazy {
        Settings.System.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID) ?: "null"
    }

    val appInfo: AppInfo by lazy {
        val appInfo = AppInfo()
        @Suppress("DEPRECATION")
        appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.GET_ACTIVITIES)
            ?.let {
                appInfo.versionName = it.versionName!!
                // Single-keystore fork: no variant split. The beta update channel is
                // gated by the signature hash stored in resolveBetaUpdateVariant.
                appInfo.appVariant = AppVariant.OFFICIAL

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    appInfo.versionCode = it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    appInfo.versionCode = it.versionCode.toLong()
                }
            }
        appInfo
    }

    @Suppress("DEPRECATION")
    private val sha256Signatures: List<String> by lazy {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appCtx.packageManager.getPackageInfo(
                appCtx.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            appCtx.packageManager.getPackageInfo(
                appCtx.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }
        signatures.orEmpty().map {
            DigestUtil.sha256Hex(it.toByteArray()).uppercase()
        }
    }

    val betaUpdateVariant: AppVariant? by lazy {
        resolveBetaUpdateVariant(appCtx.packageName, sha256Signatures)
    }

    val charsets =
        arrayListOf("UTF-8", "GB2312", "GB18030", "GBK", "Unicode", "UTF-16", "UTF-16LE", "ASCII")

    @Keep
    data class AppInfo(
        var versionCode: Long = 0L,
        var versionName: String = "",
        var appVariant: AppVariant = AppVariant.UNKNOWN
    )

    /**
     * The authority of a FileProvider defined in a <provider> element in your app's manifest.
     */
    const val authority = BuildConfig.APPLICATION_ID + ".fileProvider"

}

internal fun resolveBetaUpdateVariant(
    packageName: String,
    signatureSha256: List<String>
): AppVariant? {
    val currentSignature = signatureSha256.singleOrNull() ?: return null
    // Beta update channel is gated by a separate signing key.
    // The beta signature SHA-256 must be set by the fork maintainer
    // and matched against the installed APK's signing certificate.
    // With a single-keystore fork there is no beta channel by default.
    if (!currentSignature.equals(BETA_SIGNATURE, ignoreCase = true)) return null
    return AppVariant.BETA_RELEASE
}
