package com.example.allrecorder.widgets

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import com.example.allrecorder.R
import com.example.allrecorder.RecordingService

// [FIX] Add this missing import for Arrangement
import androidx.glance.layout.Spacer

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

// --- UI Components ---

object AppWidgetTheme {
    // Background: White in Day, Black in Night
    val background = ColorProvider(day = Color.White, night = Color.Black)

    // Content (Icons/Text): Black in Day, White in Night
    val content = ColorProvider(day = Color.Black, night = Color.White)
}

@Composable
fun RecordButton(isRecording: Boolean, modifier: GlanceModifier = GlanceModifier) {
    val iconId = if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic

    // Transparent background for the button itself
    Box(
        modifier = modifier
            .clickable(onClick = actionRunCallback<ToggleRecordingAction>()),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(iconId),
            contentDescription = if (isRecording) "Stop" else "Record",
            colorFilter = ColorFilter.tint(AppWidgetTheme.content),
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

                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(AppWidgetTheme.background)
                        .cornerRadius(16.dp),
                    contentAlignment = Alignment.Center
                ) {
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

                // Manual Dark Mode check for RemoteViews text color
                val isNightMode = (context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

                val timerTextColor = if (isNightMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK

                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(AppWidgetTheme.background)
                        .cornerRadius(16.dp)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isRecording) {
                        // IDLE: Single item, centered
                        RecordButton(isRecording = false, modifier = GlanceModifier.size(48.dp))
                    } else {
                        // RECORDING: Icon + Timer with equal distribution
                        // [FIX] Since 'Arrangement' might be tricky in older Glance versions,
                        // we use Spacer with defaultWeight to push items apart equally.
                        Row(
                            modifier = GlanceModifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Left Spacer
                            Spacer(modifier = GlanceModifier.defaultWeight())

                            // 2. Stop Icon
                            RecordButton(isRecording = true, modifier = GlanceModifier.size(48.dp))

                            // 3. Middle Spacer
                            Spacer(modifier = GlanceModifier.defaultWeight())

                            // 4. Timer
                            AndroidRemoteViews(
                                remoteViews = RemoteViews(LocalContext.current.packageName, R.layout.widget_chronometer).apply {
                                    setChronometer(R.id.widget_chronometer, baseTime, null, true)
                                    setTextColor(R.id.widget_chronometer, timerTextColor)
                                }
                            )

                            // 5. Right Spacer
                            Spacer(modifier = GlanceModifier.defaultWeight())
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
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)

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