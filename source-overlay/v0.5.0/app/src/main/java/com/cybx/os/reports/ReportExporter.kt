package com.cybx.os.reports

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.cybx.os.agent.TaskRecord
import java.io.OutputStream

object ReportExporter {
    fun html(record: TaskRecord): String {
        val findings = if (record.findings.isEmpty()) {
            "<tr><td colspan='3'>No open TCP ports observed</td></tr>"
        } else {
            record.findings.joinToString("") {
                "<tr><td>${escape(it.host)}</td><td>${it.port}</td><td>${escape(it.service)}</td></tr>"
            }
        }
        val logs = record.logLines.joinToString("\n") { escape(it) }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CybX Security Lab Report</title>
<style>
body{font-family:Arial,sans-serif;background:#0b120f;color:#e9f5ed;margin:0;padding:24px}
main{max-width:900px;margin:auto}.card{background:#142019;border:1px solid #2a4935;border-radius:14px;padding:18px;margin:14px 0}
h1,h2{color:#72f59a}small{color:#a9bcae}table{width:100%;border-collapse:collapse}td,th{padding:10px;border-bottom:1px solid #35503e;text-align:left}
pre{white-space:pre-wrap;overflow-wrap:anywhere;color:#c6d7cb}.tag{color:#ffc66d}
</style></head><body><main>
<h1>CybX Security Lab Report</h1><small>Generated ${escape(record.timeLabel)} · Task ID ${escape(record.id)}</small>
<div class="card"><h2>Scope and status</h2><p>Target: <b>${escape(record.target)}</b></p><p>Status: ${escape(record.status)}</p>
<p>Open TCP services: <b>${record.openCount}</b></p><p>Original request: ${escape(record.request)}</p></div>
<div class="card"><h2>Observed services</h2><table><thead><tr><th>Host</th><th>Port</th><th>Service</th></tr></thead><tbody>$findings</tbody></table></div>
<div class="card"><h2>Analysis</h2><p>${escape(record.summary)}</p></div>
<div class="card"><h2>Task logs</h2><pre>$logs</pre></div>
<div class="card"><small>Method: bounded TCP connect checks against a user-approved RFC1918 private lab scope. Results are observations, not proof of vulnerability. No exploitation was performed.</small></div>
</main></body></html>"""
    }

    fun writePdf(record: TaskRecord, output: OutputStream) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val normal = Paint().apply { textSize = 10f; color = android.graphics.Color.BLACK; isAntiAlias = true }
        val heading = Paint().apply { textSize = 18f; color = android.graphics.Color.rgb(22, 99, 52); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val subHeading = Paint().apply { textSize = 12f; color = android.graphics.Color.rgb(22, 99, 52); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val lines = mutableListOf<Pair<String, Paint>>()
        lines += "CybX Security Lab Report" to heading
        lines += "Generated: ${record.timeLabel}" to normal
        lines += "Task ID: ${record.id}" to normal
        lines += "" to normal
        lines += "Scope and status" to subHeading
        lines += "Target: ${record.target}" to normal
        lines += "Status: ${record.status} · Open TCP services: ${record.openCount}" to normal
        lines += "Request: ${record.request}" to normal
        lines += "" to normal
        lines += "Observed services" to subHeading
        if (record.findings.isEmpty()) {
            lines += "No open TCP ports observed." to normal
        } else {
            record.findings.forEach { lines += "${it.host}  TCP/${it.port}  ${it.service}" to normal }
        }
        lines += "" to normal
        lines += "Analysis" to subHeading
        wrap(record.summary, 88).forEach { lines += it to normal }
        lines += "" to normal
        lines += "Task logs" to subHeading
        record.logLines.forEach { wrap(it, 92).forEach { line -> lines += line to normal } }
        lines += "" to normal
        wrap("Method: bounded TCP connect checks against a user-approved RFC1918 private lab scope. Results are observations, not proof of vulnerability. No exploitation was performed.", 92)
            .forEach { lines += it to normal }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = 54f
        for ((text, paint) in lines) {
            if (y > pageHeight - 50) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = 54f
            }
            if (text.isNotEmpty()) canvas.drawText(text.take(105), 42f, y, paint)
            y += if (paint === heading) 30f else if (paint === subHeading) 23f else 17f
        }
        document.finishPage(page)
        document.writeTo(output)
        document.close()
    }

    private fun wrap(value: String, limit: Int): List<String> {
        if (value.isBlank()) return listOf("")
        val result = mutableListOf<String>()
        var current = StringBuilder()
        value.split(Regex("\\s+")).forEach { word ->
            if (current.isNotEmpty() && current.length + word.length + 1 > limit) {
                result += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
