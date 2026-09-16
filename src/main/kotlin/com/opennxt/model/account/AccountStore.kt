package com.opennxt.model.account

import com.opennxt.Constants
import mu.KotlinLogging
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AccountStore(val path: Path) : AutoCloseable {
    companion object {
        private val logger = KotlinLogging.logger { }

        const val SCHEMA_VERSION = 1

        const val PBKDF2_ITERATIONS = 210_000

        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        private const val HASH_PREFIX = "pbkdf2-sha256"

        val DEFAULT_PATH: Path = Constants.DATA_PATH.resolve("accounts.sqlite")

        val instance: AccountStore by lazy { AccountStore(DEFAULT_PATH) }

        private val random = SecureRandom()

        fun hashPassword(password: String): String {
            val salt = ByteArray(SALT_BYTES)
            random.nextBytes(salt)
            val key = pbkdf2(password, salt, PBKDF2_ITERATIONS)
            return "$HASH_PREFIX\$$PBKDF2_ITERATIONS\$${hex(salt)}\$${hex(key)}"
        }

        fun verifyPassword(password: String, stored: String): Boolean {
            val parts = stored.split('$')
            if (parts.size != 4 || parts[0] != HASH_PREFIX)
                throw IllegalStateException(
                    "unparseable password hash (expected $HASH_PREFIX\$iter\$salt\$key): '${stored.take(24)}...'"
                )
            val iterations = parts[1].toIntOrNull()
                ?: throw IllegalStateException("non-numeric iteration count in stored hash: '${parts[1]}'")
            val salt = unhex(parts[2])
            val expected = unhex(parts[3])
            val actual = pbkdf2(password, salt, iterations)
            return MessageDigest.isEqual(expected, actual)
        }

        private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BYTES * 8)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd-length hex string" }
            return ByteArray(s.length / 2) { i ->
                ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
            }
        }
    }

    enum class AuthResult(val success: Boolean) {
        OK(true),

        AUTO_REGISTERED(true),

        WRONG_PASSWORD(false),

        NO_ACCOUNT(false),
    }

    private val connection: Connection

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        val config = SQLiteConfig()
        config.setJournalMode(SQLiteConfig.JournalMode.WAL)
        connection = config.createConnection("jdbc:sqlite:$path")
        try {
            openOrCreateSchema()
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
        logger.info { "Opened account database $path (schema v$SCHEMA_VERSION, WAL)" }
    }

    private fun openOrCreateSchema() {
        val hasMeta = tableExists("meta")
        if (!hasMeta) {
            if (tableExists("accounts") || tableExists("saves")) {
                throw IllegalStateException(
                    "Account database $path has account tables but no schema_version; " +
                            "move it aside or restore a backup."
                )
            }
            createSchema()
            return
        }
        val version = connection.createStatement().use { st ->
            st.executeQuery("SELECT schema_version FROM meta").use { rs ->
                if (!rs.next()) null else rs.getInt(1)
            }
        } ?: throw IllegalStateException(
            "Account database $path has no schema_version row; restore a backup or move it aside."
        )
        if (version != SCHEMA_VERSION) {
            throw IllegalStateException(
                "Account database $path is schema version $version, expected $SCHEMA_VERSION; " +
                        "refusing to open it."
            )
        }
    }

    private fun createSchema() {
        connection.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE meta (schema_version INTEGER NOT NULL)")
            st.executeUpdate("INSERT INTO meta (schema_version) VALUES ($SCHEMA_VERSION)")
            st.executeUpdate(
                """CREATE TABLE accounts (
                       username        TEXT NOT NULL COLLATE NOCASE,
                       pass_hash       TEXT NOT NULL,
                       created_ms      INTEGER NOT NULL,
                       last_login_ms   INTEGER,
                       auto_registered INTEGER NOT NULL DEFAULT 0
                   )"""
            )
            st.executeUpdate("CREATE UNIQUE INDEX accounts_username ON accounts (username COLLATE NOCASE)")
            st.executeUpdate(
                """CREATE TABLE saves (
                       username TEXT NOT NULL COLLATE NOCASE,
                       blob     TEXT NOT NULL,
                       saved_ms INTEGER NOT NULL
                   )"""
            )
            st.executeUpdate("CREATE UNIQUE INDEX saves_username ON saves (username COLLATE NOCASE)")
        }
        logger.info { "Created fresh account schema v$SCHEMA_VERSION at $path" }
    }

    @Synchronized
    private fun tableExists(name: String): Boolean {
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { return it.next() }
        }
    }

    @Synchronized
    fun register(
        username: String,
        password: String,
        autoRegistered: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotEmpty()) { "password must not be empty" }
        if (exists(username)) return false
        val hash = hashPassword(password)
        connection.prepareStatement(
            "INSERT INTO accounts (username, pass_hash, created_ms, last_login_ms, auto_registered) VALUES (?, ?, ?, NULL, ?)"
        ).use { st ->
            st.setString(1, username)
            st.setString(2, hash)
            st.setLong(3, nowMs)
            st.setInt(4, if (autoRegistered) 1 else 0)
            st.executeUpdate()
        }
        if (autoRegistered) {
            logger.warn { "Auto-registered account '$username' on first login" }
        } else {
            logger.info { "Registered account '$username'" }
        }
        return true
    }

    @Synchronized
    fun exists(username: String): Boolean {
        connection.prepareStatement("SELECT 1 FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { return it.next() }
        }
    }

    @Synchronized
    fun authenticate(
        username: String,
        password: String,
        autoRegister: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): AuthResult {
        val stored = connection.prepareStatement("SELECT pass_hash FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (stored == null) {
            if (!autoRegister) return AuthResult.NO_ACCOUNT
            if (!register(username, password, autoRegistered = true, nowMs = nowMs)) {
                return authenticate(username, password, autoRegister = false, nowMs = nowMs)
            }
            stampLastLogin(username, nowMs)
            return AuthResult.AUTO_REGISTERED
        }
        if (!verifyPassword(password, stored)) return AuthResult.WRONG_PASSWORD
        stampLastLogin(username, nowMs)
        return AuthResult.OK
    }

    @Synchronized
    private fun stampLastLogin(username: String, nowMs: Long) {
        connection.prepareStatement("UPDATE accounts SET last_login_ms = ? WHERE username = ?").use { st ->
            st.setLong(1, nowMs)
            st.setString(2, username)
            st.executeUpdate()
        }
    }

    @Synchronized
    fun lastLoginMs(username: String): Long? {
        connection.prepareStatement("SELECT last_login_ms FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                val v = rs.getLong(1)
                return if (rs.wasNull()) null else v
            }
        }
    }

    @Synchronized
    fun autoRegistered(username: String): Boolean? {
        connection.prepareStatement("SELECT auto_registered FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) != 0 else null }
        }
    }

    data class SaveRow(val blob: String, val savedMs: Long)

    @Synchronized
    fun storeSave(username: String, save: PlayerSave, nowMs: Long = System.currentTimeMillis()) {
        if (!exists(username))
            throw IllegalStateException("cannot store a save for unknown account '$username' - register it first")
        val json = save.toJson()
        connection.prepareStatement(
            "INSERT INTO saves (username, blob, saved_ms) VALUES (?, ?, ?) " +
                    "ON CONFLICT (username) DO UPDATE SET blob = excluded.blob, saved_ms = excluded.saved_ms"
        ).use { st ->
            st.setString(1, username)
            st.setString(2, json)
            st.setLong(3, nowMs)
            st.executeUpdate()
        }
    }

    @Synchronized
    fun loadSave(username: String): PlayerSave? {
        val row = saveRow(username) ?: return null
        return PlayerSave.fromJson(row.blob)
    }

    @Synchronized
    fun saveRow(username: String): SaveRow? {
        connection.prepareStatement("SELECT blob, saved_ms FROM saves WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs ->
                return if (rs.next()) SaveRow(rs.getString(1), rs.getLong(2)) else null
            }
        }
    }

    override fun close() {
        connection.close()
    }
}
