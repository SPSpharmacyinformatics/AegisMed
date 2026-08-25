package com.aegismed.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.domain.DoseStatus
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Exporters {

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    suspend fun buildCsv(context: Context): String {
        val db = AegisDatabase.get(context)
        val allMeds = db.medicationDao().observeAll().first()
        val nameById = allMeds.associate { it.id to it.name }
        val sb = StringBuilder()
        sb.appendLine("medication,scheduled_for,taken_at,status,verified_via,amount")
        for (log in db.doseLogDao().all()) {
            val sched = Instant.ofEpochMilli(log.scheduledFor).atZone(ZoneId.systemDefault()).format(tsFmt)
            val taken = log.takenAt?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(tsFmt)
            } ?: ""
            sb.appendLine(
                "\"${nameById[log.medicationId] ?: "unknown"}\",\"$sched\",\"$taken\"," +
                    "${statusName(log.statusOrdinal)},${
                        com.aegismed.app.domain.VerificationMode.entries[log.verifiedViaOrdinal].label
                    },${log.amount}"
            )
        }
        return sb.toString()
    }

    private fun statusName(ord: Int): String =
        when (DoseStatus.entries.getOrElse(ord) { DoseStatus.MISSED }) {
            DoseStatus.TAKEN -> "taken"
            DoseStatus.SKIPPED -> "skipped"
            DoseStatus.MISSED -> "missed"
            else -> "pending"
        }

    suspend fun buildAdherenceSeries(context: Context, days: Int = 30): List<Triple<LocalDate, Int, Int>> {
        val stats = com.aegismed.app.data.repo.MedRepository.adherenceStats(context, days)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return (days - 1 downTo 0).map { off ->
            val d = today.minusDays(off.toLong())
            val v = stats[d] ?: (0 to 0)
            Triple(d, v.first, v.second)
        }
    }

    fun writeShareableCsv(context: Context, csv: String): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "aegismed_dose_log_${System.currentTimeMillis()}.csv")
        f.writeText(csv)
        return FileProvider.getUriForFile(context, "com.aegismed.app.fileprovider", f)
    }

    suspend fun buildPdf(context: Context): File {
        val db = AegisDatabase.get(context)
        val series = buildAdherenceSeries(context)
        val totalTaken = series.sumOf { it.second }
        val totalMissed = series.sumOf { it.third }
        val rate = if (totalTaken + totalMissed > 0) totalTaken * 100 / (totalTaken + totalMissed) else 100

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val c = page.canvas

        val title = Paint().apply { color = Color.rgb(14, 42, 71); textSize = 22f; isFakeBoldText = true }
        val head = Paint().apply { color = Color.BLACK; textSize = 13f; isFakeBoldText = true }
        val body = Paint().apply { color = Color.DKGRAY; textSize = 11f }
        val barOk = Paint().apply { color = Color.rgb(46, 125, 50) }
        val barBad = Paint().apply { color = Color.rgb(198, 40, 40) }
        val axis = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        c.drawText("AegisMed Adherence Report", 48f, 60f, title)
        c.drawText("Generated ${LocalDate.now()} — last ${series.size} days", 48f, 82f, body)

        c.drawText("Overall adherence: $rate%", 48f, 120f, head)
        c.drawText("Confirmed doses: $totalTaken    Missed/skipped: $totalMissed", 48f, 138f, body)

        val chartTop = 170f
        val chartH = 220f
        val chartW = 480f
        c.drawLine(48f, chartTop + chartH, 528f, chartTop + chartH, axis)

        val n = series.size.coerceAtLeast(1)
        val slotW = chartW / n
        val maxVal = maxOf(1, series.maxOf { it.second + it.third })
        series.forEachIndexed { i, (_, taken, missed) ->
            val x = 48f + i * slotW
            val hT = if (maxVal > 0) chartH * taken / maxVal else 0f
            val hM = if (maxVal > 0) chartH * missed / maxVal else 0f
            c.drawRect(x, chartTop + chartH - hT, x + slotW * 0.7f, chartTop + chartH, barOk)
            c.drawRect(x, chartTop + chartH - hT - hM, x + slotW * 0.7f, chartTop + chartH - hT, barBad)
        }

        var y = chartTop + chartH + 50f
        c.drawText("Medications", 48f, y, head); y += 18f
        for (m in db.medicationDao().listActive()) {
            val tierName = when (com.aegismed.app.domain.Tier.entries[m.tierOrdinal]) {
                com.aegismed.app.domain.Tier.CRITICAL -> "Critical"
                com.aegismed.app.domain.Tier.STANDARD -> "Standard"
                com.aegismed.app.domain.Tier.ELECTIVE -> "Elective"
            }
            c.drawText("• ${m.name} (${tierName})", 56f, y, body); y += 16f
            if (y > 790) break
        }

        pdf.finishPage(page)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "aegismed_report_${LocalDate.now().format(dayFmt)}.pdf")
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return out
    }

    fun sharePdfUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "com.aegismed.app.fileprovider", file)
}
