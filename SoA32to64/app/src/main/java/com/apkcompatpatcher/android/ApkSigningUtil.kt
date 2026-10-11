package com.apkcompatpatcher.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** A custom, user-supplied keystore to sign with instead of the app's
 *  auto-generated AndroidKeyStore key. [keystoreFile] is a plain .jks/.keystore
 *  (or .p12) file the user picked via the file picker. */
data class CustomKeystoreConfig(
    val keystoreFile: File,
    val alias: String,
    val password: String
)

/**
 * Signs the patched APK using a self-signed key generated once and kept in
 * Android's own AndroidKeyStore - not a .jks file, so there is nothing to
 * lose or type a password for. Reusing the SAME key across every patch run
 * (rather than a fresh one per output, like the PC tool does) means
 * patching the SAME game twice in a row, or two different games, all end
 * up signed by one consistent identity - so re-installing an updated patch
 * of a game you already installed does not require uninstalling first.
 *
 * Uses `com.android.tools.build:apksig` - the exact library `apksigner`
 * and Android Studio itself use - for the actual v1/v2/v3 signing, so the
 * signature format itself is produced by well-tested, standard code; only
 * the key handling here is custom.
 */
object ApkSigningUtil {

    private const val ALIAS = "apkcompatpatcher-key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    private fun getOrCreateKey(): Pair<PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        if (!ks.containsAlias(ALIAS)) {
            Logger.log("signing: no signing key yet, generating one (one-time, reused for every future patch)...")
            val notBefore = Calendar.getInstance()
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=ApkCompatPatcher"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(notBefore.time)
                .setCertificateNotAfter(notAfter.time)
                .build()

            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
            kpg.initialize(spec)
            kpg.generateKeyPair()
            Logger.log("signing: key generated.")
        }

        val privateKey = ks.getKey(ALIAS, null) as PrivateKey
        @Suppress("UNCHECKED_CAST")
        val certChain = ks.getCertificateChain(ALIAS).map { it as X509Certificate }
        return privateKey to certChain
    }

    private fun loadCustomKey(config: CustomKeystoreConfig): Pair<PrivateKey, List<X509Certificate>> {
        Logger.log("signing: loading custom keystore ${config.keystoreFile.name}, alias '${config.alias}'...")
        // Try the common store types in turn - a plain .jks/.keystore file is
        // almost always JKS (the historic Java default, and what `keytool`
        // produces unless told otherwise), a .p12 is PKCS12; trying both
        // covers what someone is likely to hand this screen without asking
        // them to know the difference.
        var lastError: Exception? = null
        for (type in listOf("JKS", "PKCS12")) {
            try {
                val ks = KeyStore.getInstance(type)
                FileInputStream(config.keystoreFile).use { ins -> ks.load(ins, config.password.toCharArray()) }
                val privateKey = ks.getKey(config.alias, config.password.toCharArray()) as? PrivateKey
                    ?: throw ApkPatcher.PatchException(
                        "alias '${config.alias}' not found in the keystore, or it isn't a private key"
                    )
                @Suppress("UNCHECKED_CAST")
                val certChain = ks.getCertificateChain(config.alias).map { it as X509Certificate }
                Logger.log("signing: custom keystore loaded as $type")
                return privateKey to certChain
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw ApkPatcher.PatchException(
            "could not open the keystore as JKS or PKCS12 (wrong password, wrong alias, or unsupported " +
                "format): ${lastError?.message}",
            lastError
        )
    }

    /** Signs [unsignedApk] in place, writing the finished, installable APK to [outputApk].
     *  Uses [customKeystore] if given, otherwise the app's own auto-generated AndroidKeyStore key. */
    fun signApk(unsignedApk: File, outputApk: File, customKeystore: CustomKeystoreConfig? = null) {
        val (privateKey, certChain) = if (customKeystore != null) {
            loadCustomKey(customKeystore)
        } else {
            getOrCreateKey()
        }

        val signerConfig = ApkSigner.SignerConfig.Builder(
            customKeystore?.alias ?: "patchkey",
            privateKey,
            certChain
        ).build()

        Logger.log("signing: applying v1+v2+v3 signature (apksig)...")
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
        signer.sign()
        Logger.log("signing: done -> ${outputApk.name}")
    }
}
