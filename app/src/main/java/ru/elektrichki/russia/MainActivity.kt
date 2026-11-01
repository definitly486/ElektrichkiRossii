package ru.elektrichki.russia

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val BrandBlue = Color(0xFF1769AA)
private val PageBg = Color(0xFFF3F5F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ElektrichkiApp() }
    }
}

data class Train(
    val number: String,
    val title: String,
    val departure: String,
    val arrival: String,
    val duration: String,
    val stops: String
)

class RaspApi(private val client: OkHttpClient = OkHttpClient()) {
    private val base = "https://api.rasp.yandex-net.ru/v3.0"

    suspend fun search(apiKey: String, from: String, to: String, date: String): List<Train> {
        val url = "$base/search/?apikey=${enc(apiKey)}&from=${enc(from)}&to=${enc(to)}&date=$date&transport_types=suburban&format=json&lang=ru_RU"
        val req = Request.Builder().url(url).get().build()
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException("Ошибка API ${r.code}: ${body.take(250)}")
                body
            }
        }
        val root = JSONObject(response)
        val arr = root.optJSONArray("segments") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val thread = item.optJSONObject("thread") ?: JSONObject()
                val fromObj = item.optJSONObject("from") ?: JSONObject()
                val toObj = item.optJSONObject("to") ?: JSONObject()
                add(
                    Train(
                        number = thread.optString("number", "Электричка"),
                        title = thread.optString("title", "${fromObj.optString("title")} — ${toObj.optString("title")}"),
                        departure = clock(item.optString("departure")),
                        arrival = clock(item.optString("arrival")),
                        duration = duration(item.optDouble("duration", 0.0)),
                        stops = item.optString("stops", "")
                    )
                )
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clock(s: String): String = try {
        val time = s.substringAfter('T').take(5)
        if (time.length == 5) time else "—"
    } catch (_: Exception) { "—" }
    private fun duration(seconds: Double): String {
        if (seconds <= 0) return ""
        val mins = (seconds / 60).toInt()
        return "${mins / 60} ч ${mins % 60} мин"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElektrichkiApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(prefs.getString("from_name", "") ?: "") }
    var to by remember { mutableStateOf(prefs.getString("to_name", "") ?: "") }
    var fromCode by remember { mutableStateOf(prefs.getString("from_code", "") ?: "") }
    var toCode by remember { mutableStateOf(prefs.getString("to_code", "") ?: "") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)) }
    var results by remember { mutableStateOf<List<Train>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Train?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember { RaspApi() }

    MaterialTheme(colorScheme = lightColorScheme(primary = BrandBlue, background = PageBg)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Электрички России", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("Расписание пригородных поездов", fontSize = 11.sp, color = Color.Gray)
                        }
                    },
                    actions = { TextButton(onClick = { showSettings = true }) { Text("API") } }
                )
            },
            containerColor = PageBg
        ) { padding ->
            if (selected != null) {
                Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                    TextButton(onClick = { selected = null }) { Text("← Назад к результатам") }
                    Text(selected!!.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("Поезд № ${selected!!.number}")
                    Text("${selected!!.departure}  →  ${selected!!.arrival}", fontSize = 24.sp, color = BrandBlue)
                    Text("В пути: ${selected!!.duration}")
                    if (selected!!.stops.isNotBlank()) Text("Остановки: ${selected!!.stops}")
                    Text("Для полного списка остановок потребуется запрос нитки /thread/ по uid.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(16.dp)
                        ) {
                            Text("Куда отправимся?", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = from, onValueChange = { from = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Откуда — название станции") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = to, onValueChange = { to = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Куда — название станции") },
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CalendarMonth, null, tint = BrandBlue)
                                TextButton(onClick = {
                                    val c = Calendar.getInstance()
                                    DatePickerDialog(context, { _, y, m, d ->
                                        date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                                }) { Text(date) }
                            }
                            Button(
                                onClick = {
                                    if (apiKey.isBlank()) { showSettings = true; return@Button }
                                    if (fromCode.isBlank() || toCode.isBlank()) {
                                        error = "Для поиска нужны коды станций. В этой стартовой версии выбор станций через справочник ещё не подключён."
                                        return@Button
                                    }
                                    loading = true; error = null; results = emptyList()
                                    scope.launch {
                                        try { results = api.search(apiKey, fromCode, toCode, date) }
                                        catch (e: Exception) { error = e.message ?: "Не удалось получить расписание" }
                                        finally { loading = false }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Search, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Найти электрички")
                            }
                            Text("Источник расписания: Яндекс.Расписания", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                    if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
                    if (results.isNotEmpty()) item { Text("Найденные электрички", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    items(results) { train ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { selected = train },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(train.departure, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                    Icon(Icons.Default.ArrowForward, null, Modifier.padding(horizontal = 10.dp), tint = BrandBlue)
                                    Text(train.arrival, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.FavoriteBorder, null, tint = Color.Gray)
                                }
                                Text(train.title, fontWeight = FontWeight.Medium)
                                Text("№ ${train.number}  •  ${train.duration}", color = Color.Gray, fontSize = 13.sp)
                                Text("Подробнее о маршруте →", color = BrandBlue, fontSize = 13.sp)
                            }
                        }
                    }
                    if (results.isEmpty() && !loading && error == null) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🚆", fontSize = 42.sp)
                                Text("Расписание рядом", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Укажите станции и дату, чтобы найти пригородные поезда.", color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Настройки API") },
            text = {
                Column {
                    Text("Вставьте персональный ключ Яндекс.Расписаний.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API-ключ") }, singleLine = true
                    )
                    Text("Ключ хранится локально на устройстве.", fontSize = 11.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("api_key", apiKey).apply()
                    showSettings = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } }
        )
    }
}
