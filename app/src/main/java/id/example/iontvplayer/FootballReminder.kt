package id.example.iontvplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FootballReminder {
    private const val PREFS = "iontv_football_reminder"
    private const val KEY_MATCHES = "scheduled_matches"
    private const val ACTION_MATCH = "id.example.iontvplayer.MATCH_ALARM"
    private const val FIVE_MINUTES = 5 * 60 * 1000L
    private const val LOOKAHEAD = 30L * 24 * 60 * 60 * 1000

    private data class League(
        val name: String,
        val file: String,
        val zone: String
    )

    private val leagues = listOf(
        League("Premier League Inggris", "en.1.json", "Europe/London"),
        League("Bundesliga Jerman", "de.1.json", "Europe/Berlin"),
        League("La Liga Spanyol", "es.1.json", "Europe/Madrid"),
        League("Serie A Italia", "it.1.json", "Europe/Rome"),
        League("Ligue 1 Prancis", "fr.1.json", "Europe/Paris")
    )

    fun refresh(context: Context, onComplete: (Int, Int) -> Unit) {
        val appContext = context.applicationContext

        Thread {
            val now = System.currentTimeMillis()
            val found = mutableMapOf<Int, JSONObject>()
            var successfulLeagues = 0
            val successfulLeagueFiles = mutableSetOf<String>()

            for (league in leagues) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL(
                        "https://raw.githubusercontent.com/openfootball/" +
                            "football.json/master/2026-27/${league.file}"
                    )
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 15000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "ION-TV-Football-Reminder/1.0")
                    }

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        continue
                    }

                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                    val root = JSONObject(text)
                    val matches = root.optJSONArray("matches") ?: continue
                    successfulLeagues++
                    successfulLeagueFiles.add(league.file)

                    for (i in 0 until matches.length()) {
                        val match = matches.optJSONObject(i) ?: continue

                        val date = match.optString("date", "").trim()
                        val time = match.optString("time", "").trim()
                        val home = match.optString("team1", "").trim()
                        val away = match.optString("team2", "").trim()

                        if (date.isEmpty() || time.isEmpty() ||
                            home.isEmpty() || away.isEmpty()
                        ) continue

                        // Lewati hanya pertandingan yang benar-benar memiliki skor.
                        // Array score kosong berarti pertandingan belum dimainkan.
                        val score = match.opt("score")
                        val hasScore = when (score) {
                            null, JSONObject.NULL -> false
                            is JSONArray -> (0 until score.length()).any {
                                score.optString(it).isNotBlank()
                            }
                            is JSONObject -> score.length() > 0
                            is String -> score.isNotBlank()
                            else -> true
                        }
                        if (hasScore) continue

                        val kickoff = try {
                            val parser = SimpleDateFormat(
                                "yyyy-MM-dd HH:mm",
                                Locale.US
                            ).apply {
                                isLenient = false
                                timeZone = TimeZone.getTimeZone(league.zone)
                            }
                            parser.parse("$date $time")?.time ?: continue
                        } catch (_: Exception) {
                            continue
                        }

                        val alarmAt = kickoff - FIVE_MINUTES
                        if (alarmAt <= now || kickoff > now + LOOKAHEAD) continue

                        val stableKey =
                            "${league.file}|$date|$time|$home|$away"
                        val id = stableKey.hashCode() and 0x7fffffff
                        if (id == 0) continue

                        found[id] = JSONObject().apply {
                            put("id", id)
                            put("home", home)
                            put("away", away)
                            put("league", league.name)
                            put("kickoff", kickoff)
                            put("alarmAt", alarmAt)
                        }
                    }
                } catch (_: Exception) {
                    // Liga yang gagal diunduh dilewati; liga lain tetap diproses.
                } finally {
                    connection?.disconnect()
                }
            }

            if (successfulLeagues == 0) {
                Handler(Looper.getMainLooper()).post {
                    onComplete(-1, 0)
                }
                return@Thread
            }

            // Pertahankan jadwal lama dari liga yang gagal diunduh.
            val combined = mutableMapOf<Int, JSONObject>()
            found.forEach { (id, match) -> combined[id] = match }

            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previousText = prefs.getString(KEY_MATCHES, null)
            if (previousText != null) {
                try {
                    val previous = JSONArray(previousText)
                    for (i in 0 until previous.length()) {
                        val oldMatch = previous.optJSONObject(i) ?: continue
                        val oldId = oldMatch.optInt("id", 0)
                        val oldAlarmAt = oldMatch.optLong("alarmAt", 0L)
                        if (oldId == 0 || oldAlarmAt <= now) continue

                        val oldLeague = oldMatch.optString("league")
                        val sourceFile = leagues.firstOrNull {
                            it.name == oldLeague
                        }?.file ?: continue

                        if (sourceFile in successfulLeagueFiles) continue
                        combined[oldId] = oldMatch
                    }
                } catch (_: Exception) {
                    // Abaikan data lama yang tidak valid.
                }
            }

            val ordered = combined.values.sortedBy { it.optLong("alarmAt") }

            // Batalkan alarm lama lalu jadwalkan kembali daftar gabungan.
            cancelSavedAlarms(appContext)

            val saved = JSONArray()
            ordered.forEach { saved.put(it) }

            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MATCHES, saved.toString())
                .apply()

            ordered.forEach { scheduleOne(appContext, it) }

            Handler(Looper.getMainLooper()).post {
                onComplete(ordered.size, successfulLeagues)
            }
        }.start()
    }

    fun hasExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return false
        return manager.canScheduleExactAlarms()
    }

    fun rescheduleStored(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_MATCHES, null) ?: return

        try {
            val saved = JSONArray(text)
            for (i in 0 until saved.length()) {
                val match = saved.optJSONObject(i) ?: continue
                scheduleOne(context.applicationContext, match)
            }
        } catch (_: Exception) {
            // Data tersimpan tidak valid; pembaruan manual dapat memperbaikinya.
        }
    }

    private fun cancelSavedAlarms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_MATCHES, null) ?: return

        try {
            val saved = JSONArray(old)
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (i in 0 until saved.length()) {
                val id = saved.optJSONObject(i)?.optInt("id") ?: continue
                val pending = pendingIntent(context, id, null)
                if (pending != null) {
                    manager.cancel(pending)
                    pending.cancel()
                }
            }
        } catch (_: Exception) {
            // Lanjutkan pembaruan walaupun ada alarm lama yang tidak terbaca.
        }
    }

    private fun pendingIntent(
        context: Context,
        id: Int,
        match: JSONObject?
    ): PendingIntent? {
        val intent = Intent(context, FootballAlarmReceiver::class.java).apply {
            action = ACTION_MATCH
            data = Uri.parse("iontv-football://match/$id")
            if (match != null) {
                putExtra("match_id", id)
                putExtra(
                    "match_title",
                    "${match.optString("home")} vs ${match.optString("away")}"
                )
                putExtra("league", match.optString("league"))
            }
        }

        val flags = if (match == null) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }

    private fun scheduleOne(context: Context, match: JSONObject) {
        val id = match.optInt("id", 0)
        val alarmAt = match.optLong("alarmAt", 0L)
        if (id == 0 || alarmAt <= System.currentTimeMillis()) return

        val pending = pendingIntent(context, id, match) ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !manager.canScheduleExactAlarms()
            ) {
                // Fallback: waktu pengingat bisa terlambat jika Android menunda alarm.
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmAt,
                    pending
                )
            } else {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmAt,
                    pending
                )
            }
        } catch (_: SecurityException) {
            try {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmAt,
                    pending
                )
            } catch (_: Exception) {
                // Izin dapat berubah sewaktu aplikasi berjalan.
            }
        } catch (_: Exception) {
            // Satu alarm gagal tidak boleh menghentikan alarm pertandingan lain.
        }
    }
}
