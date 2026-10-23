package com.muhasib.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

private fun backupDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups").apply { mkdirs() }
fun backups(ctx: Context): List<File> = backupDir(ctx).listFiles { f -> f.name.endsWith(".db") }?.sortedByDescending { it.lastModified() } ?: emptyList()
fun makeBackup(ctx: Context, db: Db, tag: String): File {
    val f = File(backupDir(ctx), "inv-$tag-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.db")
    db.snapshotTo(f); return f
}
/** Keeps the 7 newest automatic snapshots. */
fun autoBackup(ctx: Context, db: Db) {
    if (db.conf(421) != "1") return
    val newest = backups(ctx).firstOrNull { it.name.startsWith("inv-auto-") }
    if (newest != null && System.currentTimeMillis() - newest.lastModified() < 30 * 60 * 1000) return
    runCatching {
        makeBackup(ctx, db, "auto")
        backups(ctx).filter { it.name.startsWith("inv-auto-") }.drop(7).forEach { it.delete() }
    }
}

private fun onMain(f: () -> Unit) { Handler(Looper.getMainLooper()).post { f() } }

// ------------------------------------------------------------------------------------- yearly closing
@Composable
fun ClosingScreen(n: Nav) {
    val ctx = LocalContext.current
    val last = remember(n.tick) { n.db.lastClosing() }
    var date by remember { mutableStateOf("${Calendar_year() - 1}-12-31") }
    var ask by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Page(n, "إقفال سنوي") {
        Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("آخر إقفال: ${last ?: "لا يوجد"}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("عند الإقفال يتم:\n• ترحيل أرصدة العملاء والموردين والصناديق وبقية الحسابات الميزانية كقيود افتتاحية في اليوم التالي للتاريخ.\n" +
                "• ترحيل بضاعة آخر المدة لكل مخزن بآخر سعر شراء حتى ذلك التاريخ.\n• ترحيل صافي الأرباح والخسائر إلى رأس المال، دون ترحيل حسابات المبيعات والمشتريات والمصاريف.\n" +
                "• حذف جميع الفواتير والسندات والقيود حتى تاريخ الإقفال نهائياً وقفل تلك الفترة من أي تعديل.", fontSize = 14.sp)
            Text("تُحفظ نسخة احتياطية تلقائياً قبل التنفيذ، ويمكن استرجاعها من القائمة الجانبية.", color = Credit, fontSize = 14.sp)
            Field(date, { date = it }, "تاريخ الإقفال (yyyy-MM-dd)")
            PillButton("تنفيذ الإقفال", Modifier.align(Alignment.CenterHorizontally)) { typed = ""; ask = true }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false }, title = { Text("تأكيد الإقفال السنوي") },
        text = { Column { Text("سيتم حذف كل البيانات حتى $date. اكتب كلمة (إقفال) للتأكيد."); Field(typed, { typed = it }, "إقفال") } },
        confirmButton = {
            TextButton(onClick = {
                if (typed.trim() != "إقفال") { n.toast("اكتب كلمة إقفال للتأكيد"); return@TextButton }
                ask = false
                val bk = runCatching { makeBackup(ctx, n.db, "before-closing") }.getOrNull()
                if (bk == null) { n.toast("تعذر حفظ النسخة الاحتياطية، لم يتم الإقفال"); return@TextButton }
                val e = n.db.closeYear(date.trim())
                if (e == null) { n.toast("تم الإقفال حتى $date"); n.bump() } else n.toast(e)
            }) { Text("إقفال", color = Debit) }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("إلغاء") } }
    )
}
private fun Calendar_year(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

// ------------------------------------------------------------------------------------- app lock
@Composable
fun LockScreen(db: Db, onOk: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val bio = db.conf(402) == "1"
    fun tryBio() {
        val act = ctx.findActivity() ?: return
        if (BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("فتح التطبيق").setSubtitle("استخدم بصمتك")
                .setNegativeButtonText("استخدام الرمز").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build()
        )
    }
    LaunchedEffect(Unit) { if (bio) tryBio() }
    Column(Modifier.fillMaxSize()) {
        AppBar("التطبيق مقفل", null)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                pin, { pin = it; err = "" }, Modifier.fillMaxWidth(), label = { Text("رمز القفل") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Pink)
            )
            if (err.isNotEmpty()) Text(err, color = Debit)
            PillButton("فتح", Modifier.align(Alignment.CenterHorizontally)) {
                if (sha256(pin) == db.conf(401)) onOk() else err = "الرمز غير صحيح"
            }
            if (bio) TextButton(onClick = { tryBio() }, Modifier.align(Alignment.CenterHorizontally)) { Text("👆 البصمة") }
        }
    }
}

@Composable
fun SecurityScreen(n: Nav) {
    val ctx = LocalContext.current
    var r by remember { mutableIntStateOf(0) }
    val has = remember(r) { n.db.conf(401).isNotBlank() }
    val bio = remember(r) { n.db.conf(402) == "1" }
    val canBio = remember { BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS }
    var pin by remember { mutableStateOf("") }; var pin2 by remember { mutableStateOf("") }; var cur by remember { mutableStateOf("") }
    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
    Page(n, "خيارات الأمان") {
        Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (has) "قفل التطبيق مفعّل" else "قفل التطبيق غير مفعّل", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (has) Field(cur, { cur = it }, "الرمز الحالي", kb = kb)
            Field(pin, { pin = it }, if (has) "الرمز الجديد (4 أرقام على الأقل)" else "رمز القفل (4 أرقام على الأقل)", kb = kb)
            Field(pin2, { pin2 = it }, "تأكيد الرمز", kb = kb)
            PillButton(if (has) "تغيير الرمز" else "تفعيل القفل", Modifier.align(Alignment.CenterHorizontally)) {
                when {
                    has && sha256(cur) != n.db.conf(401) -> n.toast("الرمز الحالي غير صحيح")
                    pin.length < 4 -> n.toast("الرمز قصير (4 أرقام على الأقل)")
                    pin != pin2 -> n.toast("الرمزان غير متطابقين")
                    else -> { n.db.setConf(401, "app_lock", sha256(pin)); pin = ""; pin2 = ""; cur = ""; r++; n.toast("تم الحفظ") }
                }
            }
            if (has) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (canBio) "الدخول بالبصمة" else "الدخول بالبصمة (غير متاح على هذا الجهاز)", Modifier.weight(1f))
                    Switch(bio, { n.db.setConf(402, "app_lock_bio", if (it) "1" else "0"); r++ }, enabled = canBio)
                }
                HorizontalDivider()
                Text("لإلغاء القفل أدخل الرمز الحالي ثم اضغط:", color = Color.Gray)
                OutlinedButton(onClick = {
                    if (sha256(cur) != n.db.conf(401)) n.toast("أدخل الرمز الحالي أولاً")
                    else { n.db.setConf(401, "app_lock", ""); n.db.setConf(402, "app_lock_bio", "0"); cur = ""; r++; n.toast("تم إلغاء القفل") }
                }, Modifier.fillMaxWidth()) { Text("إلغاء قفل التطبيق", color = Debit) }
            }
            Text("يُقفل التطبيق عند فتحه، وبعد العودة إليه بعد أكثر من دقيقة في الخلفية. ننبّه: إن نسيت الرمز لا يمكن استرجاعه إلا بحذف بيانات التطبيق.", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

// ------------------------------------------------------------------------------------- printing
@Composable
fun PrintOptionsScreen(n: Nav) {
    var width by remember { mutableStateOf(n.db.conf(302).ifBlank { "58" }) }
    var contact by remember { mutableStateOf(n.db.conf(304) != "0") }
    Page(n, "خيارات الطباعة") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("عرض ورق الطابعة الحرارية", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(width == "58", { width = "58"; n.db.setConf(302, "paper_width", "58") }, label = { Text("58 مم") }); Spacer(Modifier.width(8.dp))
                FilterChip(width == "80", { width = "80"; n.db.setConf(302, "paper_width", "80") }, label = { Text("80 مم") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("طباعة العنوان وأرقام التواصل في الإيصال", Modifier.weight(1f))
                Switch(contact, { contact = it; n.db.setConf(304, "receipt_contact", if (it) "1" else "0") })
            }
            Text("بيانات المنشأة (الاسم والعنوان والهاتف) تُحرَّر من الإعدادات ← البيانات الشخصية، وتظهر في ملفات PDF والإيصالات.", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

fun printReceipt(ctx: Context, db: Db, billId: Long, toast: (String) -> Unit) {
    val mac = db.conf(301)
    if (mac.isBlank()) return toast("اختر الطابعة الحرارية من الإعدادات أولاً")
    val doc = db.billDoc(billId) ?: return toast("الفاتورة غير موجودة")
    val header = db.storeHeader(); val w = if (db.conf(302) == "80") 576 else 384; val contact = db.conf(304) != "0"
    toast("جارٍ الطباعة...")
    Thread {
        val err = try { Out.sendBluetooth(mac, Out.escPos(Out.receiptBitmap(doc, header, w, contact))) } catch (e: Exception) { "تعذرت الطباعة: ${e.message}" }
        onMain { toast(err ?: "تمت الطباعة") }
    }.start()
}

@Composable
fun ThermalScreen(n: Nav) {
    val ctx = LocalContext.current
    var r by remember { mutableIntStateOf(0) }
    val saved = remember(r) { n.db.conf(301) to n.db.conf(303) }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    fun load() {
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { n.toast("البلوتوث غير متوفر"); return }
        devices = try { @Suppress("MissingPermission") ad.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) n.toast("لا توجد أجهزة مقترنة. اقرن الطابعة من إعدادات بلوتوث الجهاز أولاً")
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) load() else n.toast("لم تُمنح صلاحية البلوتوث") }
    Page(n, "الطابعة الحرارية") {
        Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الطابعة المختارة: ${saved.second.ifBlank { "لا توجد" }}", fontWeight = FontWeight.Bold)
            PillButton("بحث في الأجهزة المقترنة", Modifier.align(Alignment.CenterHorizontally)) {
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    perm.launch(Manifest.permission.BLUETOOTH_CONNECT) else load()
            }
            @Suppress("MissingPermission")
            devices.forEach { d ->
                Text(d.name ?: d.address, Modifier.fillMaxWidth().clickable {
                    n.db.setConf(301, "printer_mac", d.address); n.db.setConf(303, "printer_name", d.name ?: d.address); r++
                }.padding(vertical = 12.dp), fontSize = 17.sp)
                HorizontalDivider()
            }
            if (saved.first.isNotBlank()) {
                OutlinedButton(onClick = {
                    val doc = Db.Doc("طباعة تجريبية", 1, n.db.today(), "", "", listOf(listOf("صنف تجريبي", "1 قطعة", "10.00", "10.00")), listOf("الصافي" to "10.00"))
                    val header = n.db.storeHeader(); val w = if (n.db.conf(302) == "80") 576 else 384; val mac = saved.first
                    Thread { val e = Out.sendBluetooth(mac, Out.escPos(Out.receiptBitmap(doc, header, w, true))); onMain { n.toast(e ?: "تمت الطباعة") } }.start()
                }, Modifier.fillMaxWidth()) { Text("طباعة صفحة تجريبية") }
                OutlinedButton(onClick = { n.db.setConf(301, "printer_mac", ""); n.db.setConf(303, "printer_name", ""); r++ }, Modifier.fillMaxWidth()) { Text("إزالة الطابعة", color = Debit) }
            }
            Text("تعمل مع الطابعات الحرارية بلوتوث التي تدعم ESC/POS. يُرسَل الإيصال كصورة فتظهر العربية سليمة على أي طابعة. لم تُجرَّب على جهاز طابعة فعلي.", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

// ------------------------------------------------------------------------------------- barcode labels
@Composable
fun BarcodeScreen(n: Nav) {
    val ctx = LocalContext.current
    val all = remember { n.db.barcodeItems() }
    val picked = remember { mutableStateMapOf<Long, Int>() }
    var q by remember { mutableStateOf("") }
    Page(n, "طابعة باركود الأصناف", actions = {
        PdfAction {
            val labels = all.filter { (picked[it.id] ?: 0) > 0 }.flatMap { i -> List(picked[i.id]!!) { i.name to i.barcode } }
            if (labels.isEmpty()) throw IllegalStateException("اختر صنفاً واحداً على الأقل")
            Out.barcodeLabels(ctx, labels)
        }
    }) {
        Field(q, { q = it }, "بحث")
        Text("اضغط على الصنف لزيادة عدد الملصقات، وزر (−) لإنقاصها. الأصناف المعروضة هي التي لها باركود فقط (Code128).", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 12.dp))
        if (all.isEmpty()) Empty("لا توجد أصناف لها باركود") else LazyColumn(Modifier.fillMaxSize()) {
            items(all.filter { it.name.contains(q, true) || it.barcode.contains(q) }) { i ->
                val c = picked[i.id] ?: 0
                Row(Modifier.fillMaxWidth().clickable { picked[i.id] = c + 1 }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(i.name, fontWeight = FontWeight.SemiBold); Text(i.barcode, fontSize = 12.sp, color = Color.Gray) }
                    if (c > 0) { Text("× $c", fontWeight = FontWeight.Bold, color = Blue); TextButton(onClick = { picked[i.id] = c - 1 }) { Text("−", fontSize = 22.sp) } }
                }
                HorizontalDivider()
            }
        }
    }
}

// ------------------------------------------------------------------------------------- alerts & notification options
@Composable
fun AlertsScreen(n: Nav) {
    val list = remember(n.tick) { n.db.alerts() }
    Page(n, "الإشعارات") {
        if (list.isEmpty()) Empty("لا توجد إشعارات") else LazyColumn(Modifier.fillMaxSize()) {
            items(list) { Text("🔔  $it", Modifier.fillMaxWidth().padding(14.dp)); HorizontalDivider() }
        }
    }
}

@Composable
fun NotifOptionsScreen(n: Nav) {
    var low by remember { mutableStateOf(n.db.conf(410) != "0") }
    var lim by remember { mutableStateOf(n.db.conf(412) != "0") }
    var th by remember { mutableStateOf(n.db.conf(411).ifBlank { "0" }) }
    Page(n, "خيارات الإشعارات") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("تنبيه نفاد/انخفاض المخزون", Modifier.weight(1f)); Switch(low, { low = it; n.db.setConf(410, "alert_stock", if (it) "1" else "0") }) }
            Field(th, { th = it; n.db.setConf(411, "alert_stock_min", it.ifBlank { "0" }) }, "تنبيه عندما تكون الكمية أقل من أو تساوي", kb = numKb)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) { Text("تنبيه الاقتراب من سقف الحساب (90%)", Modifier.weight(1f)); Switch(lim, { lim = it; n.db.setConf(412, "alert_limit", if (it) "1" else "0") }) }
            Text("تظهر التنبيهات عند الضغط على جرس الشاشة الرئيسية.", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

// ------------------------------------------------------------------------------------- data saving & other options
@Composable
fun DataSaveScreen(n: Nav) {
    val ctx = LocalContext.current
    var r by remember { mutableIntStateOf(0) }
    var auto by remember { mutableStateOf(n.db.conf(421) == "1") }
    val list = remember(r) { backups(ctx) }
    var restore by remember { mutableStateOf<File?>(null) }
    Page(n, "خيارات حفظ البيانات") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("نسخة احتياطية تلقائية عند الخروج من التطبيق (آخر 7 نسخ)", Modifier.weight(1f))
                Switch(auto, { auto = it; n.db.setConf(421, "auto_backup", if (it) "1" else "0") })
            }
            PillButton("حفظ نسخة محلية الآن", Modifier.align(Alignment.CenterHorizontally)) {
                runCatching { makeBackup(ctx, n.db, "manual") }.onSuccess { r++; n.toast("تم الحفظ") }.onFailure { n.toast("فشل الحفظ: ${it.message}") }
            }
            HorizontalDivider()
            Text("النسخ المحلية (اضغط للاسترجاع):", fontWeight = FontWeight.Bold)
            if (list.isEmpty()) Text("لا توجد نسخ", color = Color.Gray)
            list.forEach { f ->
                Text("${f.name}\n${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))} — ${f.length() / 1024} ك.ب",
                    Modifier.fillMaxWidth().clickable { restore = f }.padding(vertical = 8.dp), fontSize = 13.sp)
                HorizontalDivider()
            }
        }
    }
    restore?.let { f ->
        Confirm("استرجاع نسخة", "سيتم استبدال قاعدة البيانات الحالية بهذه النسخة. تُحفظ نسخة من الحالية قبل ذلك.", "استرجاع", onYes = {
            runCatching { makeBackup(ctx, n.db, "before-restore"); f.inputStream().use { n.db.importFrom(it) } }
                .onSuccess { n.bump(); n.toast("تم الاسترجاع") }.onFailure { n.toast("فشل الاسترجاع: ${it.message}") }
        }, onDismiss = { restore = null })
    }
}

@Composable
fun OtherOptionsScreen(n: Nav) {
    var dec by remember { mutableStateOf(DECIMALS) }
    Page(n, "خيارات أخرى") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("عدد الخانات العشرية في عرض المبالغ", fontWeight = FontWeight.Bold)
            Row { (0..3).forEach { d -> FilterChip(dec == d, { dec = d; DECIMALS = d; n.db.setConf(431, "decimals", d.toString()); n.bump() }, label = { Text("$d") }); Spacer(Modifier.width(8.dp)) } }
            Text("يؤثر على العرض والتقارير فقط؛ القيم المخزنة تبقى بدقتها الكاملة.", fontSize = 12.sp, color = Color.Gray)
        }
    }
}
