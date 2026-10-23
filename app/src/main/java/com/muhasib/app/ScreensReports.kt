package com.muhasib.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TrialScreen(n: Nav) {
    val ctx = LocalContext.current
    val currs = remember { n.db.currencies() }
    var curr by remember { mutableLongStateOf(0L) }
    val rows = remember(n.tick, curr) { n.db.trialBalance(curr) }
    val d = rows.filter { it.balance > 0 }.sumOf { it.balance }
    val c = rows.filter { it.balance < 0 }.sumOf { -it.balance }
    Page(n, "ميزان المراجعة", actions = {
        PdfAction {
            Out.table(ctx, "trial.pdf", n.db.storeHeader(), "ميزان المراجعة", listOf("العملة: ${currs.firstOrNull { it.id == curr }?.name ?: ""}"),
                listOf("الحساب", "مدين", "دائن"), listOf(2.4f, 1f, 1f),
                rows.map { listOf(it.name, if (it.balance > 0) money(it.balance) else "", if (it.balance < 0) money(-it.balance) else "") },
                listOf("إجمالي المدين" to money(d), "إجمالي الدائن" to money(c)))
        }
    }) {
        Drop("العملة", currs, curr) { curr = it }
        TableHeader(listOf("الحساب" to 2f, "مدين" to 1f, "دائن" to 1f))
        if (rows.isEmpty()) Box(Modifier.weight(1f)) { Empty() } else LazyColumn(Modifier.weight(1f)) {
            items(rows) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(r.name, Modifier.weight(2f), fontSize = 13.sp)
                    Text(if (r.balance > 0) money(r.balance) else "", Modifier.weight(1f), fontSize = 13.sp)
                    Text(if (r.balance < 0) money(-r.balance) else "", Modifier.weight(1f), fontSize = 13.sp)
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth().background(Blue).padding(14.dp)) {
            Text("الإجمالي", Modifier.weight(2f), color = Color.White, fontWeight = FontWeight.Bold)
            Text(money(d), Modifier.weight(1f), color = Color.White); Text(money(c), Modifier.weight(1f), color = Color.White)
        }
    }
}

@Composable
fun FinScreen(n: Nav, title: String, build: (Db, Long) -> Fin) {
    val ctx = LocalContext.current
    val currs = remember { n.db.currencies() }
    var curr by remember { mutableLongStateOf(0L) }
    val fin = remember(n.tick, curr) { build(n.db, curr) }
    Page(n, title, actions = {
        PdfAction {
            Out.table(ctx, "fin.pdf", n.db.storeHeader(), title, listOf("العملة: ${currs.firstOrNull { it.id == curr }?.name ?: ""}"),
                listOf("البيان", "القيمة"), listOf(3f, 1.4f), fin.rows.map { listOf(it.first, if (it.third == 1) "" else money(it.second)) })
        }
    }) {
        Drop("العملة", currs, curr) { curr = it }
        LazyColumn(Modifier.weight(1f)) {
            items(fin.rows) { (label, v, style) ->
                Row(
                    Modifier.fillMaxWidth().background(when (style) { 1 -> Blue; 2 -> Color(0xFFE3EEF6); else -> Color.Transparent }).padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Text(label, Modifier.weight(1f), color = if (style == 1) Color.White else Color.Unspecified, fontWeight = if (style != 0) FontWeight.Bold else FontWeight.Normal)
                    if (style != 1) Text(money(v), fontWeight = if (style == 2) FontWeight.Bold else FontWeight.Normal)
                }
                HorizontalDivider()
            }
        }
        Text("قيمة المخزون تقديرية بآخر سعر شراء لكل صنف، وتُحتسب بالعملة المحلية فقط.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun OthersScreen(n: Nav) {
    Page(n, "تقارير أخرى") {
        MenuRow("أرصدة العملاء") { n.push(Route("balances", 0, "أرصدة العملاء")) }
        MenuRow("أرصدة الموردين") { n.push(Route("balances", 1, "أرصدة الموردين")) }
        MenuRow("المخزون المتبقي") { n.push(Route("stockrem")) }
        MenuRow("كشف حساب") { n.push(Route("accounts")) }
    }
}

@Composable
fun BalancesScreen(n: Nav, type: Int, title: String) {
    val rows = remember(n.tick) { n.db.accounts(type).filter { it.bal.isNotEmpty() } }
    val totals = HashMap<String, Double>()
    rows.forEach { r -> r.bal.forEach { (c, v) -> totals[c] = (totals[c] ?: 0.0) + v } }
    val ctx = LocalContext.current
    Page(n, title, actions = {
        PdfAction {
            Out.table(ctx, "balances-$type.pdf", n.db.storeHeader(), title, emptyList(), listOf("الاسم", "الرصيد"), listOf(2.5f, 2f),
                rows.map { a -> listOf(a.name, a.bal.entries.joinToString(" | ") { (c, v) -> "${money(kotlin.math.abs(v))} ${if (v > 0) "عليه" else "له"} ($c)" }) },
                totals.map { (c, v) -> "الإجمالي ($c)" to "${money(kotlin.math.abs(v))} ${if (v > 0) "عليهم" else "لهم"}" })
        }
    }) {
        if (rows.isEmpty()) Box(Modifier.weight(1f)) { Empty() } else LazyColumn(Modifier.weight(1f)) {
            items(rows) { a ->
                Row(Modifier.fillMaxWidth().clickable { n.push(Route("statement", a.id, a.name)) }.padding(12.dp)) { Text(a.name, Modifier.weight(1f)); BalanceText(a.bal) }
                HorizontalDivider()
            }
        }
        Column(Modifier.fillMaxWidth().background(Blue).padding(12.dp)) {
            totals.forEach { (c, v) -> Text("الإجمالي ($c): ${money(kotlin.math.abs(v))} ${if (v > 0) "عليهم" else "لهم"}", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun StockRemainingScreen(n: Nav) {
    val rows = remember(n.tick) { n.db.items().filter { it.qty != 0.0 } }
    val ctx = LocalContext.current
    Page(n, "المخزون المتبقي", actions = {
        PdfAction {
            Out.table(ctx, "stock.pdf", n.db.storeHeader(), "المخزون المتبقي", emptyList(), listOf("الصنف", "الكمية", "القيمة"), listOf(3f, 1.4f, 1.4f),
                rows.map { listOf(it.name, "${money(it.qty)} ${it.unit}", money(it.qty * it.cost)) }, listOf("إجمالي القيمة" to money(rows.sumOf { it.qty * it.cost })))
        }
    }) {
        TableHeader(listOf("الصنف" to 2.4f, "الكمية" to 1.2f, "القيمة" to 1.3f))
        if (rows.isEmpty()) Box(Modifier.weight(1f)) { Empty() } else LazyColumn(Modifier.weight(1f)) {
            items(rows) { i ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(i.name, Modifier.weight(2.4f)); Text("${money(i.qty)} ${i.unit}", Modifier.weight(1.2f), fontSize = 12.sp); Text(money(i.qty * i.cost), Modifier.weight(1.3f), fontSize = 12.sp)
                }
                HorizontalDivider()
            }
        }
        Text("إجمالي القيمة (بآخر سعر شراء): ${money(rows.sumOf { it.qty * it.cost })}", Modifier.fillMaxWidth().background(Blue).padding(14.dp), color = Color.White, fontWeight = FontWeight.Bold)
    }
}
