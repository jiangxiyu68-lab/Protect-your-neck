package com.example.cervicalguard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.cervicalguard.core.PostureScore
import java.util.Calendar

/**
 * 坐姿日志：按分钟存各级别时长（秒）与该分钟最大头前倾角。
 * 用系统内置 SQLite，避免额外依赖；统计时聚合。
 * 注：不使用 UPSERT 语法（Android 9 的 SQLite 3.22 不支持），改用 INSERT OR IGNORE + UPDATE。
 */
class PostureDb(context: Context) : SQLiteOpenHelper(context, "posture.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        createLogs(db)
        createRelax(db)
    }

    private fun createLogs(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE logs (" +
                "minute INTEGER PRIMARY KEY," +
                "danger_sec INTEGER DEFAULT 0," +
                "warn_sec INTEGER DEFAULT 0," +
                "safe_sec INTEGER DEFAULT 0," +
                "max_forward REAL DEFAULT 0," +
                // 拍摄视角过正时角度不可信（前后位移被投影压缩、左右偏移混入），
                // 这段时长单独记，**不计入坐姿分**——否则「正视屏幕」会被误判成低头，
                // 把分数拉低，用户看到的就是「明明坐得很好，分却很低」。
                // 保留原始记录是为了可回溯（也能算出「有多久没摆对位置」）。
                "unreliable_sec INTEGER DEFAULT 0)"
        )
    }

    /** 放松操记录：一次会话一行（用户做操是稀疏事件，按次存比按分钟更合适）。 */
    private fun createRelax(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE relax (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_sec INTEGER," +
                "duration_sec INTEGER DEFAULT 0," +
                "reps INTEGER DEFAULT 0," +
                "total_reps INTEGER DEFAULT 0," +
                "avg_depth REAL DEFAULT 0," +
                "score INTEGER DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 按「从哪个版本升上来」分别处理。**不能无条件 DROP**：
        // v1 的「秒数」实为帧数（每帧 +1 就写库），受帧率影响虚高数倍、单位错误、无法换算还原，
        // 只能作废重建；但 v2 的数据是正确的，升级到 v3 必须原样保留。
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS logs")
            createLogs(db)
        }
        if (oldVersion < 3) {
            createRelax(db)
        }
        if (oldVersion < 4) {
            // v3 → v4：只加一列，**历史数据原样保留**（旧记录该列默认 0，
            // 即「当时的不可信时长未知」，不影响已有分数）。
            db.execSQL("ALTER TABLE logs ADD COLUMN unreliable_sec INTEGER DEFAULT 0")
        }
    }

    /** 落库一分钟的聚合数据（minuteSec 为该分钟起始的秒级时间戳）。 */
    fun upsertMinute(
        minuteSec: Long,
        danger: Int,
        warn: Int,
        safe: Int,
        maxForward: Double,
        unreliable: Int = 0,
    ) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO logs(minute) VALUES($minuteSec)")
        db.execSQL(
            "UPDATE logs SET danger_sec=danger_sec+$danger, warn_sec=warn_sec+$warn, " +
                "safe_sec=safe_sec+$safe, max_forward=MAX(max_forward,$maxForward), " +
                "unreliable_sec=unreliable_sec+$unreliable " +
                "WHERE minute=$minuteSec"
        )
    }

    data class Stats(
        val totalSec: Int,
        val dangerSec: Int,
        val warnSec: Int,
        val safeSec: Int,
        val maxForward: Double,
        /** 因拍摄视角过正而**不计分**的时长（秒）。 */
        val unreliableSec: Int = 0,
    )

    fun todayStats(): Stats {
        val startSec = todayStartMs() / 1000
        val c = readableDatabase.rawQuery(
            "SELECT SUM(danger_sec),SUM(warn_sec),SUM(safe_sec),MAX(max_forward)," +
                "IFNULL(SUM(unreliable_sec),0) FROM logs WHERE minute >= $startSec", null
        )
        var d = 0; var w = 0; var s = 0; var mx = 0.0; var u = 0
        if (c.moveToFirst()) {
            d = c.getInt(0); w = c.getInt(1); s = c.getInt(2)
            mx = c.getDouble(3); u = c.getInt(4)
        }
        c.close()
        return Stats(d + w + s, d, w, s, mx, u)
    }

    /**
     * 一个时间桶的汇总（日报按小时、周报/月报按天）。
     * 注：按固定 3600/86400 秒切桶——中国无夏令时，本地时区下正确。
     */
    data class Bucket(
        val startSec: Long,
        val dangerSec: Int,
        val warnSec: Int,
        val safeSec: Int,
        val maxForward: Double,
        /** 因拍摄视角过正而**不计分**的时长（秒）。 */
        val unreliableSec: Int = 0,
    ) {
        val totalSec: Int get() = dangerSec + warnSec + safeSec
    }

    /**
     * 今天按小时汇总（24 桶，索引 = 小时，用于日报）。
     * 用 SQL 的 GROUP BY 直接聚合成 24 行，而不是把当天所有分钟行捞进内存。
     */
    fun todayHourly(): List<Bucket> =
        bucketQuery(startSec = todayStartMs() / 1000, spanSec = 3600L, count = 24)

    /**
     * 最近 [days] 天（含今天，最后一桶 = 今天）按天汇总，用于周报 / 长周期趋势。
     * 同样在 SQL 里分桶：30 天也只有 ≤30 行返回。
     */
    fun lastDays(days: Int): List<Bucket> =
        bucketQuery(
            startSec = todayStartMs() / 1000 - (days - 1) * 86400L,
            spanSec = 86400L,
            count = days
        )

    /** 通用分桶查询：SQL 侧 GROUP BY，缺失的桶补零。 */
    private fun bucketQuery(startSec: Long, spanSec: Long, count: Int): List<Bucket> {
        val found = HashMap<Int, Bucket>(count)
        readableDatabase.rawQuery(
            "SELECT (minute - $startSec) / $spanSec AS b," +
                " SUM(danger_sec), SUM(warn_sec), SUM(safe_sec), MAX(max_forward)," +
                " IFNULL(SUM(unreliable_sec),0)" +
                " FROM logs WHERE minute >= $startSec GROUP BY b",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val i = c.getInt(0)
                if (i < 0 || i >= count) continue
                found[i] = Bucket(
                    startSec + i * spanSec,
                    c.getInt(1), c.getInt(2), c.getInt(3), c.getDouble(4), c.getInt(5)
                )
            }
        }
        return (0 until count).map {
            found[it] ?: Bucket(startSec + it * spanSec, 0, 0, 0, 0.0, 0)
        }
    }

    /** 一行分钟日志（导出 CSV 用；minuteSec 为该分钟起始的秒级时间戳）。 */
    data class LogRow(
        val minuteSec: Long,
        val dangerSec: Int,
        val warnSec: Int,
        val safeSec: Int,
        val maxForward: Double,
        val unreliableSec: Int = 0,
    ) {
        val totalSec: Int get() = dangerSec + warnSec + safeSec
    }

    /** 全部日志（按时间升序），供数据导出。 */
    fun allLogs(): List<LogRow> {
        val out = ArrayList<LogRow>()
        readableDatabase.rawQuery(
            "SELECT minute,danger_sec,warn_sec,safe_sec,max_forward,IFNULL(unreliable_sec,0) " +
                "FROM logs ORDER BY minute",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LogRow(
                        c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3),
                        c.getDouble(4), c.getInt(5)
                    )
                )
            }
        }
        return out
    }

    /** 一行放松记录。 */
    data class RelaxRow(
        val startSec: Long,
        val durationSec: Int,
        val reps: Int,
        val totalReps: Int,
        val avgDepth: Double,
        val score: Int,
    )

    fun insertRelax(r: RelaxRow) {
        writableDatabase.execSQL(
            "INSERT INTO relax(start_sec,duration_sec,reps,total_reps,avg_depth,score) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf(r.startSec, r.durationSec, r.reps, r.totalReps, r.avgDepth, r.score)
        )
    }

    /** 今天的放松情况：(次数, 总秒数, 最高分)。 */
    data class RelaxToday(val sessions: Int, val totalSec: Int, val bestScore: Int, val reps: Int)

    fun relaxToday(): RelaxToday {
        val startSec = todayStartMs() / 1000
        readableDatabase.rawQuery(
            "SELECT COUNT(*),IFNULL(SUM(duration_sec),0),IFNULL(MAX(score),0),IFNULL(SUM(reps),0) " +
                "FROM relax WHERE start_sec >= $startSec",
            null
        ).use { c ->
            return if (c.moveToFirst()) {
                RelaxToday(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
            } else {
                RelaxToday(0, 0, 0, 0)
            }
        }
    }

    /** 全部放松记录（按时间升序），供导出与趋势分析。 */
    fun allRelax(): List<RelaxRow> {
        val out = ArrayList<RelaxRow>()
        readableDatabase.rawQuery(
            "SELECT start_sec,duration_sec,reps,total_reps,avg_depth,score FROM relax ORDER BY start_sec",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RelaxRow(
                        c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3),
                        c.getDouble(4), c.getInt(5)
                    )
                )
            }
        }
        return out
    }

    /**
     * 连续达标天数：当天坐姿分 ≥ [minScore] 记一天，从今天往前数，遇到不达标/无数据就中断。
     *
     * 特殊处理：**今天数据不足时不算断**（跳过今天、从昨天开始数）——
     * 早上刚打开 App 就看到「连续 0 天」很挫败，而昨天明明达标了。
     */
    fun streakDays(minScore: Int = PostureScore.TARGET_SCORE, lookback: Int = 60): Int {
        val buckets = lastDays(lookback)
        var streak = 0
        for (i in buckets.indices.reversed()) {
            val b = buckets[i]
            val isToday = i == buckets.lastIndex
            val score = PostureScore.of(b.safeSec, b.warnSec, b.dangerSec)
            if (isToday && score == null) continue          // 今天还没攒够，不算断
            if (score != null && score >= minScore) streak++ else break
        }
        return streak
    }

    private fun todayStartMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
