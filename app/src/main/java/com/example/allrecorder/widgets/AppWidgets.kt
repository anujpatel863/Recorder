package com.example.allrecorder.widgets

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.example.allrecorder.MainActivity
import com.example.allrecorder.R
import com.example.allrecorder.RecordingService

// --- Keys for DataStore ---
object WidgetKeys {
    val isRecording = booleanPreferencesKey("is_recording")
    val startTime = longPreferencesKey("start_time")
}

// --- Receivers ---
class SimpleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SimpleRecorderWidget()
}

class TimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimerRecorderWidget()
}

// --- Action Callback ---
class ToggleRecordingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (RecordingService.isRecording) {
            val intent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
            context.startService(intent)
        } else {
            val intent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

// --- Enhanced Theme & UI Components ---

object AppWidgetTheme {
    // Idle State: White/Black (Day/Night)
    val backgroundIdle = ColorProvider(day = Color.White, night = Color(0xFF1C1B1F))
    val contentIdle = ColorProvider(day = Color.Black, night = Color.White)

    // Recording State: Red Background (Distinct visibility)
    val backgroundRecording = ColorProvider(day = Color(0xFFB3261E), night = Color(0xFF601410))
    val contentRecording = ColorProvider(day = Color.White, night = Color(0xFFF2B8B5))
}

@Composable
fun WidgetContainer(
    isRecording: Boolean,
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit
) {
    val bg = if (isRecording) AppWidgetTheme.backgroundRecording else AppWidgetTheme.backgroundIdle

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>()), // Clicking background opens app
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun RecordButton(isRecording: Boolean, modifier: GlanceModifier = GlanceModifier) {
    val iconId = if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic
    val tint = if (isRecording) AppWidgetTheme.contentRecording else AppWidgetTheme.contentIdle

    Box(
        modifier = modifier
            .clickable(onClick = actionRunCallback<ToggleRecordingAction>()),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(iconId),
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(32.dp)
        )
    }
}

// --- Widget 1: Simple (1x1 Block) ---
class SimpleRecorderWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()
                val isRecording = prefs[WidgetKeys.isRecording] ?: false

                WidgetContainer(isRecording = isRecording) {
                    RecordButton(isRecording = isRecording, modifier = GlanceModifier.fillMaxSize())
                }
            }
        }
    }
}

// --- Widget 2: Timer (Dynamic Layout) ---
class TimerRecorderWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()
                val isRecording = prefs[WidgetKeys.isRecording] ?: false
                val startTime = prefs[WidgetKeys.startTime] ?: 0L

                val baseTime = if (isRecording && startTime > 0) {
                    SystemClock.elapsedRealtime() - (System.currentTimeMillis() - startTime)
                } else {
                    SystemClock.elapsedRealtime()
                }

                val isNight = (context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

                // Logic: If Recording -> White Text (on Red bg). If Idle -> Black/White depending on theme.
                val timerTextColorInt = if (isRecording) {
                    if (isNight) 0xFFF2B8B5.toInt() else android.graphics.Color.WHITE
                } else {
                    if (isNight) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                }

                WidgetContainer(isRecording = isRecording, modifier = GlanceModifier.padding(8.dp)) {
                    if (!isRecording) {
                        // IDLE: Just the button centered
                        RecordButton(isRecording = false, modifier = GlanceModifier.size(48.dp))
                    } else {
                        // RECORDING: Stop Button (Left) + Timer (Right)
                        // Use a centered Row with specific spacing
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier.padding(horizontal = 8.dp)
                        ) {
                            // 1. Stop Button
                            RecordButton(isRecording = true, modifier = GlanceModifier.size(48.dp))

                            // 2. Spacer (Gap)
                            // Increased to 32.dp for better separation
                            Spacer(modifier = GlanceModifier.width(32.dp))

                            // 3. Timer
                            // Wrapped in Box for alignment safety, though Row handles vertical center
                            Box(contentAlignment = Alignment.Center) {
                                AndroidRemoteViews(
                                    remoteViews = RemoteViews(LocalContext.current.packageName, R.layout.widget_chronometer).apply {
                                        setChronometer(R.id.widget_chronometer, baseTime, null, true)
                                        setTextColor(R.id.widget_chronometer, timerTextColorInt)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Manager Helper ---
object WidgetManager {
    suspend fun updateWidgets(context: Context, isRecording: Boolean, startTime: Long) {
        val manager = GlanceAppWidgetManager(context)

        val simpleIds = manager.getGlanceIds(SimpleRecorderWidget::class.java)
        simpleIds.forEach { glanceId ->
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetKeys.isRecording] = isRecording
                prefs[WidgetKeys.startTime] = startTime
            }
            SimpleRecorderWidget().update(context, glanceId)
        }

        val timerIds = manager.getGlanceIds(TimerRecorderWidget::class.java)
        timerIds.forEach { glanceId ->
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetKeys.isRecording] = isRecording
                prefs[WidgetKeys.startTime] = startTime
            }
            TimerRecorderWidget().update(context, glanceId)
        }
    }
}