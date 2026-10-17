package com.bydmate.app.data.mysql

/**
 * Everything the car needs to open a JDBC session against the shared MySQL hub and to
 * partition its rows. The phone is configured with the same [vehicleId] so it reads back
 * the rows this writer tagged.
 */
data class MysqlConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val database: String,
    val vehicleId: String,
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port > 0 && user.isNotBlank() &&
            database.isNotBlank() && vehicleId.isNotBlank()

    /**
     * Oracle Connector/J, not MariaDB's: MySQL 26 dropped mysql_native_password, so auth is
     * caching_sha2_password, and MariaDB's driver mis-parses this server's DML OK-packet
     * (SELECTs return, every INSERT hangs to socket timeout). The official connector round-trips
     * an upsert against it cleanly. TLS with the self-signed cert accepted carries the password,
     * sidestepping the driver's RSA public-key retrieval that Android lacks. Connector/J timeouts
     * are in MILLISECONDS.
     *
     * `wait_timeout=45` overrides this server's 8-hour idle timeout for our session only: the hub
     * usually runs on a shared machine and the sync account is capped at a few simultaneous
     * sessions, so a connection we
     * never closed — process killed mid-sync, dropped TCP with no FIN — would hold one of those slots
     * until the server reaps it. 45 s makes that a blink instead of a workday.
     * `autoReconnect=false` stops the driver from silently opening a second session behind ours when
     * the car loses LTE.
     */
    fun jdbcUrl(): String =
        "jdbc:mysql://$host:$port/$database" +
            "?sslMode=REQUIRED&verifyServerCertificate=false&allowPublicKeyRetrieval=true" +
            "&connectTimeout=15000&socketTimeout=30000" +
            "&autoReconnect=false" +
            "&sessionVariables=wait_timeout=45,interactive_timeout=45"

    companion object {
        /** Loaded explicitly — Android's ServiceLoader for java.sql.Driver is unreliable. */
        const val DRIVER_CLASS = "com.mysql.cj.jdbc.Driver"
    }
}
