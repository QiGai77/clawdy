package com.openminis.app.clawdy.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密码箱: the user's private account data ("隐私表单"), kept only on this phone.
 *
 * Two files under `filesDir/clawdy/`, both AES-256-GCM with keys that never leave the Android
 * Keystore:
 * - `vault.meta` — what an entry *is* (type, label, site / app, which fields it has). Its key
 *   needs no unlock, so the agent can list entries by name ([VaultTools.list]) without the user.
 * - `vault.secret` — the field values. Its key is bound to the screen lock when the phone has one
 *   (`setUserAuthenticationRequired`, valid for [AUTH_VALIDITY_S] seconds after the user confirms
 *   with fingerprint / face / PIN), so nothing — not this app, not the agent — can decrypt a value
 *   without a fresh confirmation from the person holding the phone. A phone with no screen lock
 *   gets an unbound key (still Keystore-held) and the app-level confirmation only.
 *
 * Values are never logged, never put into chat history, memory, run logs or any LLM request.
 * The only readers are the edit screen (after unlock) and [VaultFill] (after the user approved a
 * fill in the approval sheet), which injects them with ACTION_SET_TEXT on the device.
 */
object Vault {
    private const val TAG = "Vault"
    private const val KS = "AndroidKeyStore"
    private const val META_KEY = "clawdy_vault_meta_v1"
    private const val SECRET_KEY = "clawdy_vault_secret_v1"
    const val AUTH_VALIDITY_S = 30

    enum class Type(val label: String) { LOGIN("账号密码"), CARD("银行卡"), ADDRESS("地址"), NOTE("备注") }

    /** One field of a form: [key] is stable (what vault_fill names), [label] is what the user reads. */
    data class Field(val key: String, val label: String, val secret: Boolean = false, val multiline: Boolean = false)

    fun fieldsOf(type: Type): List<Field> = when (type) {
        Type.LOGIN -> listOf(Field("username", "账号 / 用户名"), Field("password", "密码", secret = true), Field("phone", "手机号"), Field("email", "邮箱"))
        Type.CARD -> listOf(Field("holder", "持卡人"), Field("number", "卡号", secret = true), Field("expiry", "有效期（MM/YY）", secret = true), Field("cvv", "安全码 CVV", secret = true))
        Type.ADDRESS -> listOf(Field("name", "收件人"), Field("phone", "电话"), Field("address", "详细地址", multiline = true), Field("postcode", "邮编"))
        Type.NOTE -> listOf(Field("text", "内容", secret = true, multiline = true))
    }

    /** What may be shown or handed to the agent: never a value. */
    data class Meta(
        val id: String,
        val type: Type,
        val label: String,
        /** A website host or an Android package name; "" when it applies anywhere. */
        val target: String,
        val fieldKeys: List<String>,
        val notes: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val _entries = MutableStateFlow<List<Meta>>(emptyList())
    val entries: StateFlow<List<Meta>> = _entries.asStateFlow()

    /** Until when the vault screens stay unlocked (app-level timer; the secret key has its own). */
    @Volatile var unlockedUntil: Long = 0L
    fun unlocked(): Boolean = System.currentTimeMillis() < unlockedUntil
    fun markUnlocked(seconds: Int) { unlockedUntil = System.currentTimeMillis() + seconds.coerceAtLeast(10) * 1000L }
    fun lock() { unlockedUntil = 0L }

    @Volatile private var dir: File? = null

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "clawdy").apply { mkdirs() }
        _entries.value = loadMeta()
    }

    fun deviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    fun meta(id: String): Meta? = _entries.value.firstOrNull { it.id == id }

    /** Find an entry the way the agent names it: id, exact label, then label / site containing the words. */
    fun find(query: String): Meta? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val all = _entries.value
        return all.firstOrNull { it.id == q }
            ?: all.firstOrNull { it.label.equals(q, true) }
            ?: all.firstOrNull { it.target.equals(q, true) }
            ?: all.firstOrNull { it.label.contains(q, true) || (it.target.isNotEmpty() && (it.target.contains(q, true) || q.contains(it.target, true))) }
    }

    // ── secrets ────────────────────────────────────────────────────────────

    class LockedException : Exception("vault locked")

    /** Values of one entry. Throws [LockedException] when the screen-lock confirmation has expired. */
    fun readValues(context: Context, id: String): Map<String, String> {
        val all = loadSecrets(context)
        return all[id] ?: emptyMap()
    }

    /** Creates or replaces an entry. Needs a fresh unlock (the secret key is auth-bound). */
    fun save(context: Context, meta: Meta, values: Map<String, String>) {
        val secrets = loadSecrets(context).toMutableMap()
        val clean = values.filterValues { it.isNotEmpty() }
        secrets[meta.id] = clean
        writeSecrets(context, secrets)
        val m = meta.copy(fieldKeys = clean.keys.toList(), updatedAt = System.currentTimeMillis())
        _entries.value = _entries.value.filterNot { it.id == m.id } + m
        writeMeta()
        AppLogger.info(TAG, "saved entry ${m.id} (${m.type}, ${m.fieldKeys.size} fields)")
    }

    fun delete(context: Context, id: String) {
        val secrets = loadSecrets(context).toMutableMap()
        secrets.remove(id)
        writeSecrets(context, secrets)
        _entries.value = _entries.value.filterNot { it.id == id }
        writeMeta()
        AppLogger.info(TAG, "deleted entry $id")
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    // ── storage ────────────────────────────────────────────────────────────

    private fun metaFile() = File(dir ?: error("Vault.init not called"), "vault.meta")
    private fun secretFile() = File(dir ?: error("Vault.init not called"), "vault.secret")

    private fun loadMeta(): List<Meta> {
        val f = metaFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(String(decrypt(metaKey(), f.readText()), Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val type = runCatching { Type.valueOf(o.getString("type")) }.getOrNull() ?: return@mapNotNull null
                val keys = o.optJSONArray("fields")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                Meta(o.getString("id"), type, o.optString("label"), o.optString("target"), keys, o.optString("notes"), o.optLong("updatedAt"))
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "vault.meta unreadable: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun writeMeta() {
        val arr = JSONArray()
        _entries.value.sortedBy { it.label.lowercase() }.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("type", m.type.name).put("label", m.label).put("target", m.target)
                .put("fields", JSONArray(m.fieldKeys)).put("notes", m.notes).put("updatedAt", m.updatedAt))
        }
        atomicWrite(metaFile(), encrypt(metaKey(), arr.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun loadSecrets(context: Context): Map<String, Map<String, String>> {
        val f = secretFile()
        if (!f.exists()) return emptyMap()
        val plain = try {
            decrypt(secretKey(context), f.readText())
        } catch (e: UserNotAuthenticatedException) {
            throw LockedException()
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            AppLogger.warning(TAG, "secret key invalidated (screen lock removed?)")
            throw IllegalStateException("密码箱的密钥已失效（可能移除了锁屏），已保存的内容无法再解密", e)
        }
        val o = JSONObject(String(plain, Charsets.UTF_8))
        plain.fill(0)
        return o.keys().asSequence().associateWith { id ->
            val e = o.getJSONObject(id)
            e.keys().asSequence().associateWith { e.getString(it) }
        }
    }

    private fun writeSecrets(context: Context, all: Map<String, Map<String, String>>) {
        val o = JSONObject()
        all.forEach { (id, fields) -> o.put(id, JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }) }
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        try {
            atomicWrite(secretFile(), encrypt(secretKey(context), bytes))
        } catch (e: UserNotAuthenticatedException) {
            throw LockedException()
        } finally {
            bytes.fill(0)
        }
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    // ── crypto ─────────────────────────────────────────────────────────────

    private fun keyStore(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }

    private fun metaKey(): SecretKey = (keyStore().getKey(META_KEY, null) as? SecretKey) ?: generate(META_KEY, null)

    private fun secretKey(context: Context): SecretKey =
        (keyStore().getKey(SECRET_KEY, null) as? SecretKey) ?: generate(SECRET_KEY, context)

    /** [authContext] non-null: bind the key to the screen lock when there is one. */
    private fun generate(alias: String, authContext: Context?): SecretKey {
        fun spec(bound: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (bound) {
                    setUserAuthenticationRequired(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(AUTH_VALIDITY_S, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_S)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        val bind = authContext != null && deviceSecure(authContext)
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        return try {
            gen.init(spec(bind)); gen.generateKey()
        } catch (e: Exception) {
            if (!bind) throw e
            AppLogger.warning(TAG, "auth-bound key unavailable (${e.javaClass.simpleName}); using an unbound Keystore key")
            gen.init(spec(false)); gen.generateKey()
        }
    }

    /** Whether the stored secret key is bound to the screen lock (for the settings page to say). */
    fun keyIsAuthBound(): Boolean = runCatching {
        val key = keyStore().getKey(SECRET_KEY, null) as? SecretKey ?: return false
        val f = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, KS)
        (f.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo).isUserAuthenticationRequired
    }.getOrDefault(false)

    private fun encrypt(key: SecretKey, plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val out = c.iv + c.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, text: String): ByteArray {
        val all = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }
}
