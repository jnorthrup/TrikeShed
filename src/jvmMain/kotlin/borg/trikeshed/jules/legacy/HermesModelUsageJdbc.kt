package borg.trikeshed.jules.legacy

import borg.trikeshed.jules.HermesModelUsage

import borg.trikeshed.common.File
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

/**
 * JVM bootstrap for [HermesModelUsage]: installs the jdbc:sqlite reader.
 * The daemon calls [install] once at boot, before any panel reads the ledger.
 */
object HermesModelUsageJdbc {

    fun install() {
        HermesModelUsage.reader = HermesModelUsage.Reader { db, sql, params ->
            val props = SQLiteConfig().apply {
                setReadOnly(true)
                setBusyTimeout(2_000)
            }.toProperties()
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", props).use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    params.forEachIndexed { i, v ->
                        when (v) {
                            is Int -> ps.setInt(i + 1, v)
                            is Long -> ps.setLong(i + 1, v)
                            else -> ps.setObject(i + 1, v)
                        }
                    }
                    ps.executeQuery().use { rs ->
                        val out = ArrayList<List<Any?>>()
                        while (rs.next()) {
                            out.add(List(rs.metaData.columnCount) { col ->
                                rs.getObject(col + 1)
                            })
                        }
                        out
                    }
                }
            }
        }
    }
}
