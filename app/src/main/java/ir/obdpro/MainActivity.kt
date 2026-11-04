package ir.obdpro

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val obd = ElmConnection()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var permissionGranted by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = it.values.all { v -> v }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBtPermission()
        setContent { ObdApp(obd, permissionGranted, onRequestPermission = { requestBtPermission() }) }
    }
    private fun requestBtPermission() {
        val p = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
                else emptyArray()
        if (p.isEmpty()) permissionGranted = true
        else if (p.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) permissionGranted = true
        else permissionLauncher.launch(p)
    }
    override fun onDestroy() { scope.cancel(); obd.disconnect(); super.onDestroy() }
}

@Composable
fun ObdApp(obd: ElmConnection, permission: Boolean, onRequestPermission: () -> Unit) {
    var page by remember { mutableStateOf("داشبورد") }
    var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    var status by remember { mutableStateOf("آداپتور متصل نیست") }
    var ecu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf(mapOf<String, PidValue>()) }
    var dtcs by remember { mutableStateOf(emptyList<String>()) }
    var log by remember { mutableStateOf("") }
    var supported by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    val commands = listOf("04","05","0B","0C","0D","0F","10","11","42","2F")
    fun value(pid: String, fallback: String = "--") = values[pid]?.let {
        String.format(Locale.US, if (it.value % 1.0 == 0.0) "%.0f" else "%.1f", it.value) + " " + it.unit
    } ?: fallback

    LaunchedEffect(obd.connected) {
        if (obd.connected) {
            while (obd.connected) {
                for (pid in commands) {
                    try {
                        val response = obd.command("01$pid", 1600)
                        log = "01$pid  ←  $response"
                        val b = ObdProtocol.bytesFor(response, "41", pid)
                        if (b != null) ObdProtocol.decode(pid, b)?.let { values = values + (pid to it) }
                    } catch (e: Exception) { status = e.message ?: "خطای ارتباط" }
                    delay(70)
                }
                ecu = values.isNotEmpty()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFFF9418), secondary = Color(0xFF36B8FF),
        background = Color(0xFF050B19), surface = Color(0xFF101F3B)
    )) {
        Column(Modifier.fillMaxSize().background(Color(0xFF050B19)).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OBD PRO", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                    Text("تشخیص هوشمند خودرو", color = Color(0xFF71BFFF), fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (obd.connected) "● آداپتور متصل" else "● آداپتور قطع", color = if(obd.connected) Color.Green else Color.Red)
                    Text(if (ecu) "ECU: داده دریافت می‌شود" else "ECU: داده معتبر دریافت نشده", color = if(ecu) Color(0xFF5CE0A0) else Color.LightGray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("داشبورد","اتصال","خطاها","سنسورها","گزارش").forEach { name ->
                    FilterChip(selected = page == name, onClick = { page = name }, label = { Text(name, fontSize = 11.sp) })
                }
            }
            Spacer(Modifier.height(10.dp))
            when(page) {
                "اتصال" -> {
                    Text("دستگاه‌های بلوتوث جفت‌شده", color = Color.White, fontSize = 18.sp)
                    Button(onClick = {
                        if (!permission) onRequestPermission()
                        else try { devices = obd.pairedDevices(); status = "فهرست دستگاه‌ها به‌روز شد" } catch(e: Exception) { status=e.message ?: "خطا" }
                    }) { Text("بارگذاری دستگاه‌ها") }
                    LazyColumn { items(devices) { d ->
                        Card(Modifier.fillMaxWidth().padding(vertical=4.dp), colors=CardDefaults.cardColors(containerColor=Color(0xFF142747))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name ?: "Bluetooth device", color=Color.White)
                                    Text(d.address, color=Color.LightGray, fontSize=11.sp)
                                }
                                Button(enabled=!busy, onClick={
                                    selected=d; busy=true
                                    scope.launch {
                                        try { obd.connect(d); status="اتصال برقرار شد"; val r=obd.command("0100"); supported=ObdProtocol.supportedPids(r); log=r }
                                        catch(e: Exception) { status=e.message ?: "اتصال ناموفق" ; obd.disconnect() }
                                        busy=false
                                    }
                                }) { Text("اتصال") }
                            }
                        }
                    } }
                    OutlinedButton(onClick={obd.disconnect(); status="ارتباط قطع شد"; ecu=false}) { Text("قطع اتصال") }
                    Text(status, color=Color(0xFF8CCBFF))
                }
                "خطاها" -> {
                    Text("خطاهای موتور (OBD-II)", color=Color.White, fontSize=20.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=obd.connected && !busy, onClick={
                            busy=true; scope.launch { try { dtcs=ObdProtocol.dtcs(obd.command("03")); log="درخواست خواندن خطا انجام شد" } catch(e:Exception){log=e.message ?: "خطا"}; busy=false }
                        }) { Text("خواندن خطاها") }
                        OutlinedButton(enabled=obd.connected && !busy, onClick={
                            busy=true; scope.launch { try { obd.command("04"); dtcs=emptyList(); log="فرمان پاک‌کردن ارسال شد؛ نتیجه را با اسکن مجدد بررسی کنید" } catch(e:Exception){log=e.message ?: "خطا"}; busy=false }
                        }) { Text("پاک‌کردن خطاها") }
                    }
                    if(dtcs.isEmpty()) Text("کدی نمایش داده نشده؛ ابتدا خطاها را بخوانید.", color=Color.LightGray)
                    dtcs.forEach { Text(it, color=Color(0xFFFFA33A), fontSize=20.sp) }
                    Text("پاک‌کردن خطا ممکن است داده‌های آمادگی آلایندگی را صفر کند.", color=Color.LightGray, fontSize=11.sp)
                }
                "سنسورها" -> {
                    Text("پارامترهای زنده ECU", color=Color.White, fontSize=20.sp)
                    LazyColumn { items(commands) { pid ->
                        val v=values[pid]
                        Card(Modifier.fillMaxWidth().padding(vertical=3.dp), colors=CardDefaults.cardColors(containerColor=Color(0xFF122541))) {
                            Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(v?.name ?: "PID 01$pid", color=Color(0xFF9CCEFF))
                                Text(value(pid), color=Color.White, fontWeight=FontWeight.Bold)
                            }
                        }
                    } }
                }
                "گزارش" -> {
                    Text("گزارش ارتباط", color=Color.White, fontSize=20.sp)
                    Text("PIDهای پشتیبانی‌شده: ${supported.sorted().joinToString()}", color=Color.LightGray)
                    Text("آخرین پاسخ: $log", color=Color(0xFF8CCBFF))
                    Text("این نسخه فقط داده‌هایی را نمایش می‌دهد که ECU واقعاً پاسخ دهد.", color=Color.LightGray, fontSize=12.sp)
                }
                else -> {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp), modifier=Modifier.fillMaxWidth()) {
                        Gauge("دور موتور", values["0C"]?.value ?: 0.0, 8000.0, value("0C"), Modifier.weight(1f))
                        Gauge("سرعت خودرو", values["0D"]?.value ?: 0.0, 220.0, value("0D"), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    val cards=listOf(
                        "دمای آب موتور" to value("05"),
                        "دمای هوای ورودی" to value("0F"),
                        "بار موتور" to value("04"),
                        "ولتاژ ECU" to value("42"),
                        "فشار منیفولد" to value("0B"),
                        "دریچه گاز" to value("11")
                    )
                    cards.chunked(2).forEach { row ->
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                            row.forEach { (title,v) ->
                                Card(Modifier.weight(1f).padding(bottom=8.dp), colors=CardDefaults.cardColors(containerColor=Color(0xFF122541)), shape=RoundedCornerShape(18.dp)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(title, color=Color(0xFF8CCBFF), fontSize=12.sp)
                                        Text(v, color=Color.White, fontSize=20.sp, fontWeight=FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    Text(if(obd.connected) "داده‌ها از ELM327 خوانده می‌شوند." else "برای مشاهده داده واقعی، ابتدا ELM327 را متصل کنید.", color=Color.LightGray, fontSize=12.sp)
                }
            }
        }
    }
}

@Composable
fun Gauge(title: String, raw: Double, max: Double, shown: String, modifier: Modifier = Modifier) {
    val fraction=(raw/max).coerceIn(0.0,1.0).toFloat()
    Card(modifier.height(235.dp), colors=CardDefaults.cardColors(containerColor=Color(0xFF0D1B35)), shape=RoundedCornerShape(28.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val c=Offset(size.width/2,size.height/2+8.dp.toPx())
                val radius=min(size.width,size.height)*0.39f
                drawArc(Color(0xFF29466E), 135f, 270f, false, topLeft=Offset(c.x-radius,c.y-radius), size=androidx.compose.ui.geometry.Size(radius*2,radius*2), style=Stroke(12.dp.toPx(), cap=StrokeCap.Round))
                drawArc(Brush.sweepGradient(listOf(Color(0xFF16B9FF),Color(0xFF315BFF),Color(0xFFFF8A16))), 135f, 270f*fraction, false, topLeft=Offset(c.x-radius,c.y-radius), size=androidx.compose.ui.geometry.Size(radius*2,radius*2), style=Stroke(12.dp.toPx(), cap=StrokeCap.Round))
                for(i in 0..20) {
                    val a=Math.toRadians((135+ i*13.5).toDouble())
                    val r1=radius-4.dp.toPx(); val r2=radius-16.dp.toPx()
                    drawLine(Color(0xFFB7DFFF), Offset(c.x+cos(a).toFloat()*r1,c.y+sin(a).toFloat()*r1), Offset(c.x+cos(a).toFloat()*r2,c.y+sin(a).toFloat()*r2), 2.dp.toPx())
                }
                // عقربه اصلی
                val angle=Math.toRadians((135+270*fraction).toDouble())
                val end=Offset(c.x+cos(angle).toFloat()*radius*0.72f,c.y+sin(angle).toFloat()*radius*0.72f)
                drawLine(Color(0xFFFF9418),c,end,5.dp.toPx(),cap=StrokeCap.Round)
                drawCircle(Color(0xFFFFB347),7.dp.toPx(),c)
                // عقربه دوم: از حلقه بیرونی تا نیمه صفحه
                val inner=Offset(c.x-cos(angle).toFloat()*radius*0.42f,c.y-sin(angle).toFloat()*radius*0.42f)
                drawLine(Color(0xFFFFC05A), Offset(c.x+cos(angle).toFloat()*radius*1.03f,c.y+sin(angle).toFloat()*radius*1.03f), inner, 2.dp.toPx(), cap=StrokeCap.Round)
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.padding(top=42.dp)) {
                Text(shown, color=Color.White, fontSize=25.sp, fontWeight=FontWeight.Black)
                Text(title, color=Color(0xFF8CCBFF), fontSize=12.sp)
            }
        }
    }
}
