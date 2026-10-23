package com.muhasib.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** PDF export, system printing, Code128 barcode labels and ESC/POS thermal receipts. */
object Out {
    private const val PW = 595
    private const val PH = 842
    private const val M = 28f

    fun dir(ctx: Context): File = File(ctx.cacheDir, "share").apply { mkdirs() }

    fun share(ctx: Context, f: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun print(ctx: Context, f: File) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(f.name, PdfAdapter(f), null)
    }

    private class PdfAdapter(private val f: File) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            try {
                FileInputStream(f).use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) { cb.onWriteFailed(e.message) }
        }
    }

    // ------------------------------------------------------------------ table PDF
    private class TablePdf(val header: List<String>, val title: String, val info: List<String>, val cols: List<String>, weights: List<Float>) {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        lateinit var cv: Canvas
        var y = 0f
        var no = 0
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK; textAlign = Paint.Align.RIGHT }
        val bp = Paint(tp).apply { typeface = Typeface.DEFAULT_BOLD }
        val wp = Paint(bp).apply { color = Color.WHITE }
        val ctr = Paint(tp).apply { textAlign = Paint.Align.CENTER }
        val bg = Paint().apply { color = 0xFF0B6FAA.toInt() }
        val line = Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 0.6f }
        val widths = weights.map { (PW - 2 * M) * it / weights.sum() }

        fun cell(text: String, right: Float, w: Float, p: Paint, yy: Float) {
            val k = p.breakText(text, true, w - 8f, null)
            cv.drawText(if (k < text.length) text.substring(0, k) else text, right - 4f, yy, p)
        }
        fun rowOf(cells: List<String>, p: Paint, yy: Float) {
            var r = PW - M
            cells.forEachIndexed { i, t -> if (i < widths.size) { cell(t, r, widths[i], p, yy); r -= widths[i] } }
        }
        fun newPage() {
            finish()
            no++
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, no).create())
            page = pg; cv = pg.canvas; y = M + 6
            if (no == 1) {
                header.forEachIndexed { i, h ->
                    val p = if (i == 0) Paint(ctr).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 16f } else ctr
                    cv.drawText(h, PW / 2f, y + 12, p); y += if (i == 0) 22f else 14f
                }
                y += 6
                cv.drawText(title, PW / 2f, y + 12, Paint(ctr).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 14f }); y += 26
                info.forEach { cv.drawText(it, PW - M, y + 10, tp); y += 15 }
                y += 6
            }
            cv.drawRect(M, y, PW - M, y + 22, bg)
            rowOf(cols, wp, y + 15); y += 24
        }
        fun finish() {
            page?.let { cv.drawText("$no", PW / 2f, PH - 14f, ctr); doc.finishPage(it) }
            page = null
        }
        fun row(cells: List<String>) {
            if (page == null || y > PH - 60) newPage()
            rowOf(cells, tp, y + 12)
            cv.drawLine(M, y + 17, PW - M, y + 17, line)
            y += 19
        }
        fun total(label: String, value: String) {
            if (page == null || y > PH - 40) newPage()
            cv.drawText("$label: $value", PW - M, y + 14, bp); y += 18
        }
    }

    fun table(
        ctx: Context, name: String, header: List<String>, title: String, info: List<String>,
        cols: List<String>, weights: List<Float>, rows: List<List<String>>, footer: List<Pair<String, String>> = emptyList()
    ): File {
        val t = TablePdf(header, title, info, cols, weights)
        t.newPage()
        rows.forEach { t.row(it) }
        if (footer.isNotEmpty()) { t.y += 6; footer.forEach { t.total(it.first, it.second) } }
        t.finish()
        val f = File(dir(ctx), name)
        FileOutputStream(f).use { t.doc.writeTo(it) }
        t.doc.close()
        return f
    }

    fun billPdf(ctx: Context, d: Db.Doc, header: List<String>): File = table(
        ctx, "bill-${d.no}.pdf", header, "${d.title} رقم ${d.no}",
        listOfNotNull("التاريخ: ${d.date}", d.party.takeIf { it.isNotBlank() }?.let { "الاسم: $it" }, d.remarks.takeIf { it.isNotBlank() }?.let { "ملاحظات: $it" }),
        listOf("الصنف", "الكمية", "السعر", "الاجمالي"), listOf(3f, 1.4f, 1.2f, 1.4f), d.rows, d.totals
    )

    // ------------------------------------------------------------------ Code128 (set B) barcode labels
    private val C128 = ("212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 221312 231212 112232 122132 122231 113222 " +
        "123122 123221 223211 221132 221231 213212 223112 312131 311222 321122 321221 312212 322112 322211 212123 212321 232121 111323 " +
        "131123 131321 112313 132113 132311 211313 231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 231131 213113 " +
        "213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 111422 121124 121421 141122 141221 112214 " +
        "112412 122114 122411 142112 142211 241211 221114 413111 241112 134111 111242 121142 121241 114212 124112 124211 411212 421112 " +
        "421211 212141 214121 412121 111143 111341 131141 114113 114311 411113 411311 113141 114131 311141 411131 211412 211214 211232 2331112").split(" ")

    fun code128(s: String): String? {
        if (s.isEmpty() || s.any { it.code !in 32..126 }) return null
        val codes = ArrayList<Int>()
        codes.add(104)
        s.forEach { codes.add(it.code - 32) }
        var sum = codes[0]
        for (i in 1 until codes.size) sum += i * codes[i]
        codes.add(sum % 103); codes.add(106)
        return codes.joinToString("") { C128[it] }
    }

    /** One label per (name, barcode) × copies, 3 columns × 9 rows per A4 page. */
    fun barcodeLabels(ctx: Context, labels: List<Pair<String, String>>): File {
        val doc = PdfDocument()
        val cols = 3; val rowsPer = 9
        val lw = (PW - 2 * M) / cols; val lh = (PH - 2 * M) / rowsPer
        val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.BLACK; textAlign = Paint.Align.CENTER }
        val bar = Paint().apply { color = Color.BLACK }
        var pageNo = 0; var page: PdfDocument.Page? = null
        labels.forEachIndexed { idx, (name, code) ->
            val pos = idx % (cols * rowsPer)
            if (pos == 0) {
                page?.let { doc.finishPage(it) }
                pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create())
            }
            val cv = page!!.canvas
            val col = pos % cols; val row = pos / cols
            val left = M + col * lw; val top = M + row * lh
            val bars = code128(code) ?: return@forEachIndexed
            val modules = bars.sumOf { it.digitToInt() } + 20
            val mw = (lw - 16f) / modules
            val k = np.breakText(name, true, lw - 8f, null)
            cv.drawText(if (k < name.length) name.substring(0, k) else name, left + lw / 2, top + 12, np)
            var x = left + 8f + 10 * mw
            var isBar = true
            bars.forEach { ch ->
                val w = ch.digitToInt() * mw
                if (isBar) cv.drawRect(x, top + 18, x + w, top + lh - 18, bar)
                x += w; isBar = !isBar
            }
            cv.drawText(code, left + lw / 2, top + lh - 6, np)
        }
        page?.let { doc.finishPage(it) }
        val f = File(dir(ctx), "barcodes.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    // ------------------------------------------------------------------ thermal receipt (rendered as a bitmap, so Arabic prints correctly)
    private class RLine(val left: String?, val right: String?, val center: String?, val bold: Boolean = false, val sep: Boolean = false, val big: Boolean = false)

    fun receiptBitmap(d: Db.Doc, header: List<String>, widthPx: Int, showContact: Boolean): Bitmap {
        val base = if (widthPx > 400) 26f else 21f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = base }
        val lines = ArrayList<RLine>()
        fun wrapped(text: String, bold: Boolean) {
            val tmp = Paint(p).apply { if (bold) typeface = Typeface.DEFAULT_BOLD }
            var rest = text
            while (rest.isNotEmpty()) {
                val k = max(1, tmp.breakText(rest, true, widthPx - 16f, null))
                lines.add(RLine(null, rest.substring(0, k), null, bold)); rest = rest.substring(k)
            }
        }
        header.forEachIndexed { i, h -> if (i == 0 || showContact) lines.add(RLine(null, null, h, bold = i == 0, big = i == 0)) }
        lines.add(RLine(null, null, null, sep = true))
        lines.add(RLine(null, null, "${d.title} رقم ${d.no}", bold = true))
        lines.add(RLine(null, "التاريخ: ${d.date}", null))
        if (d.party.isNotBlank()) wrapped("الاسم: ${d.party}", false)
        if (d.remarks.isNotBlank()) wrapped("ملاحظات: ${d.remarks}", false)
        lines.add(RLine(null, null, null, sep = true))
        d.rows.forEach { r ->
            wrapped(r[0], true)
            lines.add(RLine(r[3], "${r[1]} × ${r[2]}", null))
        }
        lines.add(RLine(null, null, null, sep = true))
        d.totals.forEach { (l, v) -> lines.add(RLine(v, l, null, bold = l == "الصافي" || l == "المتبقي")) }
        lines.add(RLine(null, null, null, sep = true))
        lines.add(RLine(null, null, "شكراً لتعاملكم معنا"))
        val lh = { l: RLine -> (if (l.big) base * 1.4f else base) * 1.45f }
        val h = (lines.sumOf { lh(it).toDouble() } + 60).toInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
        var y = 8f
        for (l in lines) {
            val pp = Paint(p).apply { if (l.bold) typeface = Typeface.DEFAULT_BOLD; if (l.big) textSize = base * 1.4f }
            val base0 = y + pp.textSize
            when {
                l.sep -> { pp.strokeWidth = 2f; cv.drawLine(4f, y + base / 2, widthPx - 4f, y + base / 2, pp) }
                l.center != null -> { pp.textAlign = Paint.Align.CENTER; cv.drawText(l.center, widthPx / 2f, base0, pp) }
                else -> {
                    l.right?.let { pp.textAlign = Paint.Align.RIGHT; cv.drawText(it, widthPx - 8f, base0, pp) }
                    l.left?.let { pp.textAlign = Paint.Align.LEFT; cv.drawText(it, 8f, base0, pp) }
                }
            }
            y += lh(l)
        }
        return Bitmap.createBitmap(bmp, 0, 0, widthPx, min(h, y.toInt() + 20))
    }

    fun escPos(bmp: Bitmap): ByteArray {
        val w = bmp.width - bmp.width % 8; val h = bmp.height; val wb = w / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40))
        val px = IntArray(w)
        var y = 0
        while (y < h) {
            val bh = min(128, h - y)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (wb and 0xFF).toByte(), (wb shr 8).toByte(), (bh and 0xFF).toByte(), (bh shr 8).toByte()))
            for (r in 0 until bh) {
                bmp.getPixels(px, 0, w, 0, y + r, w, 1)
                for (bx in 0 until wb) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val c = px[bx * 8 + bit]
                        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                        if (lum < 128) b = b or (0x80 shr bit)
                    }
                    out.write(b)
                }
            }
            y += bh
        }
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00))
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    fun sendBluetooth(mac: String, data: ByteArray): String? {
        return try {
            val ad = BluetoothAdapter.getDefaultAdapter() ?: return "البلوتوث غير متوفر على الجهاز"
            if (!ad.isEnabled) return "فعّل البلوتوث أولاً"
            ad.cancelDiscovery()
            val sock = ad.getRemoteDevice(mac).createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            sock.connect()
            val o = sock.outputStream
            var i = 0
            while (i < data.size) {
                val n = min(2048, data.size - i)
                o.write(data, i, n); o.flush(); i += n
                Thread.sleep(40)
            }
            Thread.sleep(600)
            sock.close()
            null
        } catch (e: Exception) { "تعذرت الطباعة: ${e.message}" }
    }
}
