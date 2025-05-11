package com.example.healthconnectheartrate

import android.os.Bundle
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.*
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class HeartRateDisplayItem(
    val bpm: Long,
    val time: String,
    val timestamp: Long
) {
    val coreTemp: Long
        get() = bpm
}

private var exportUriHandler: ((Uri) -> Unit)? = null

fun exportToCsv(context: Context, uri: Uri, data: List<HeartRateDisplayItem>) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            val writer = outputStream.bufferedWriter()
            writer.write("Time,BPM,Core Temp\n")
            data.forEach { item ->
                writer.write("\"${item.time}\",${item.bpm},${item.coreTemp}\n")
            }
            writer.flush()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// Kalman Filter Core Temp Calculation Variables
var ct: Double = 36.97
var v: Double = 0.0
var hrHistory: MutableList<Long> = mutableListOf(1, 2, 3, 4)
val gamma = 0.022
val sigma = 18.88
val a1 = 4.5714
val b1 = 384.4286
val c1 = 7887.1
val a2 = 4.5714
val b2 = 384.4286
val c2 = 7899.76

fun calculateCoreTemp(bpm: Long): Long {
    if (hrHistory.size < 5) {
        hrHistory.add(bpm)
        return (ct * 100).toLong()
    }

    hrHistory.add(bpm)
    if (hrHistory.size > 5) {
        hrHistory.removeAt(0)
    }

    val hr_ma_now = hrHistory.takeLast(5).average()
    val hr_ma_old = hrHistory.first().toDouble()
    val delta_hr = hr_ma_now - hr_ma_old

    v += gamma * gamma

    val (a, b, c, hr_model) = if (delta_hr < 0) {
        val cVal = -2 * a2 * ct + b2
        val model = -a2 * ct * ct + b2 * ct - c2
        Quadruple(a2, b2, cVal, model)
    } else {
        val cVal = -2 * a1 * ct + b1
        val model = -a1 * ct * ct + b1 * ct - c1
        Quadruple(a1, b1, cVal, model)
    }

    val k = (v * c) / (v * c * c + sigma * sigma)
    ct = ct + k * (bpm.toDouble() - hr_model)
    v = (1 - k * c) * v

    return (ct * 100).toLong()
}

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectClient: HealthConnectClient
    private val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthConnectClient = HealthConnectClient.getOrCreate(this)

        val permissionRequest =
            registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { grantedPermissions ->
                // Trigger recomposition after permission is granted
            }

        val exportToCsvLauncher =
            registerForActivityResult(CreateDocument("text/csv")) { uri ->
                uri?.let {
                    exportUriHandler?.invoke(it)
                }
            }

        setContent {
            MaterialTheme {
                HeartRateScreen(
                    healthConnectClient = healthConnectClient,
                    onRequestPermission = {
                        permissionRequest.launch(permissions)
                    },
                    onExportCsv = { uriHandler ->
                        exportUriHandler = uriHandler
                        exportToCsvLauncher.launch("heart_rate_data.csv")
                    }
                )
            }
        }
    }
}

@Composable
fun HeartRateScreen(
    healthConnectClient: HealthConnectClient,
    onRequestPermission: () -> Unit,
    onExportCsv: ((Uri) -> Unit) -> Unit
) {
    var heartRates by remember { mutableStateOf<List<HeartRateDisplayItem>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun loadHeartRates() {
        heartRates = getAllHeartRates(healthConnectClient)
    }

    LaunchedEffect(Unit) {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        hasPermission = granted.containsAll(
            setOf(
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getWritePermission(HeartRateRecord::class)
            )
        )

        if (hasPermission) {
            coroutineScope.launch {
                loadHeartRates()
                while (true) {
                    delay(30_000)
                    loadHeartRates()
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Heart Rate", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            if (!hasPermission) {
                Button(onClick = onRequestPermission) {
                    Text("Grant Permission")
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Time", modifier = Modifier.weight(0.5f))
                    Text("BPM", modifier = Modifier.weight(0.25f))
                    Text("Core Temp", modifier = Modifier.weight(0.25f))
                }

                Divider()

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(heartRates) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.time, modifier = Modifier.weight(0.5f))
                            Text("${item.bpm}", modifier = Modifier.weight(0.25f))
                            Text(
                                text = String.format("%.2f °C", calculateCoreTemp(item.bpm) / 100.0),
                                modifier = Modifier.weight(0.25f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onExportCsv { uri ->
                            exportToCsv(context, uri, heartRates)
                        }
                    }) {
                        Text("Export to CSV")
                    }
                    Button(onClick = {
                        coroutineScope.launch {
                            loadHeartRates()
                        }
                    }) {
                        Text("Refresh Data")
                    }
                }
            }
        }
    }
}

suspend fun getAllHeartRates(healthConnectClient: HealthConnectClient): List<HeartRateDisplayItem> {
    return try {
        val endTime = ZonedDateTime.now().toInstant()
        val startTime = endTime.minus(100, ChronoUnit.DAYS)

        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        )

        val formatter = DateTimeFormatter.ofPattern("dd MMM, hh:mm a")
            .withZone(ZoneId.of("Asia/Kolkata"))

        response.records.flatMap { record ->
            record.samples.map { sample ->
                HeartRateDisplayItem(
                    bpm = sample.beatsPerMinute,
                    time = formatter.format(sample.time),
                    timestamp = sample.time.toEpochMilli()
                )
            }
        }.sortedByDescending { it.timestamp }
    } catch (e: Exception) {
        listOf(HeartRateDisplayItem(0, "Error: ${e.localizedMessage}", 0))
    }
}
