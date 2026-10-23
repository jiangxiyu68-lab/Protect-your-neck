package com.example.cervicalguard.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 坐姿日志导出为 CSV。
 *
 * 两个刻意的细节：
 *  1. 写 **UTF-8 BOM**——否则 Windows Excel 打开中文表头会乱码（国内用户大概率用 Excel）。
 *  2. 行尾用 **CRLF**——同上，Windows 系工具更省心。
 * 内容保持「纯数据表」：不在文件里插免责声明行，免得 Excel/pandas 解析时多出一行脏数据；
 * 免责声明放在 App 的导出对话框里。
 */
object CsvExporter {

    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    data class Result(val file: File, val rows: Int)

    /**
     * 写到 App 外部私有目录（`Android/data/<包名>/files/`）：
     * 不需要任何存储权限，Android 9 上文件管理器也能直接看到。
     * 外部存储不可用时退回内部 `filesDir`。
     */
    fun export(context: Context, rows: List<PostureDb.LogRow>): Result {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "posture_${fileFmt.format(Date())}.csv")

        val sb = StringBuilder()
        // 「未计分(秒)」= 拍摄视角过正、角度不可信而排除的时长（v1.1 起）。
        // 导出时保留它，方便复盘「为什么这天时长和分数对不上」。
        sb.append("时间,危险(秒),注意(秒),安全(秒),未计分(秒),合计(秒),最大头前倾(度)\r\n")
        for (r in rows) {
            sb.append(timeFmt.format(Date(r.minuteSec * 1000))).append(',')
                .append(r.dangerSec).append(',')
                .append(r.warnSec).append(',')
                .append(r.safeSec).append(',')
                .append(r.unreliableSec).append(',')
                .append(r.totalSec).append(',')
                .append(String.format(Locale.CHINA, "%.1f", r.maxForward))
                .append("\r\n")
        }

        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + sb.toString().toByteArray(Charsets.UTF_8))
        return Result(file, rows.size)
    }
}
