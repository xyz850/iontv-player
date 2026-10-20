package id.cyberneon.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FootballBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                FootballReminder.rescheduleStored(context)
            }
        }
    }
}
