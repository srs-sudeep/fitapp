package com.example.healthconnectheartrate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.io.OutputStream
data class HeartRateDisplayItem(
    val bpm: Long,
    val time: String,
    val timestamp: Long
) {
    val coreTemp: Long
        get() = bpm  //i have adjusted here
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
                // Handle the result: Recomposition in Composable will handle UI update
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
                    onRequestPermission = { permissionRequest.launch(permissions) },
                    onExportCsv = { uriHandler ->
                        exportUriHandler = uriHandler
                        exportToCsvLauncher.launch("heart_rate_data.csv")
                    }
                )
            }
        }
    }
}
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
        return (ct * 100).toLong()  // Return default CT initially
    }

    // Update HR moving average
    hrHistory.add(bpm)
    if (hrHistory.size > 5) {
        hrHistory.removeAt(0)
    }
    val hr_ma_now = hrHistory.takeLast(5).average()
    val hr_ma_old = hrHistory.first().toDouble()
    val delta_hr = hr_ma_now - hr_ma_old

    // Prediction step
    v += gamma * gamma

    val a: Double
    val b: Double
    val c: Double
    val hr_model: Double

    if (delta_hr < 0) {
        // Recovery phase
        a = a2
        b = b2
        c = -2 * a2 * ct + b2
        hr_model = -a2 * ct * ct + b2 * ct - c2
    } else {
        // Exercise phase
        a = a1
        b = b1
        c = -2 * a1 * ct + b1
        hr_model = -a1 * ct * ct + b1 * ct - c1
    }

    // Kalman Gain
    val k = (v * c) / (v * c * c + sigma * sigma)

    // Update step
    ct = ct + k * (bpm.toDouble() - hr_model)
    v = (1 - k * c) * v

    return (ct * 100).toLong()  // Return CT in hundredths (e.g., 3697 = 36.97°C)
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
                while (true) {
                    heartRates = getAllHeartRates(healthConnectClient)
                    delay(30_000)
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

                HorizontalDivider()

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

                Row {
                    Button(onClick = {
                        onExportCsv { uri ->
                            exportToCsv(context, uri, heartRates)
                        }
                    }) {
                        Text("Export to CSV")
                    }
                    Button(onClick = {
                        coroutineScope.launch {
                            heartRates = getAllHeartRates(healthConnectClient)
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
