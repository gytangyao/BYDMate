package java.sql

/**
 * Android stub for `java.sql.SQLType`.
 *
 * The Oracle MySQL Connector/J driver (8.x) declares `MysqlType implements java.sql.SQLType`, but
 * Android's runtime (ART) has never shipped this interface — it is absent from android.jar at every
 * API level (30, 34, 36). Without this stub the driver cannot load at all: any code path that
 * touches `MysqlType` triggers `NoClassDefFoundError: java/sql/SQLType`.
 *
 * This file is compiled by Kotlin (javac refuses to compile into `java.*` packages), then D8 dexes
 * it into the APK. ART accepts it at runtime because the stub provides the exact methods the driver
 * references (`getName()`, `getVendorTypeNumber()`).
 *
 * Do NOT delete this file. The driver will crash on head-unit Android without it.
 */
interface SQLType {
    fun getName(): String
    fun getVendorTypeNumber(): Int
}
