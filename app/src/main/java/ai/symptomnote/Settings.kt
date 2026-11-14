package ai.symptomnote

import android.content.Context

/** 사용자 설정(SharedPreferences). */
object Settings {
    private const val FILE = "settings"
    private const val AUTO_START = "autoStartRecording"

    /** 주행 시작 시 자동으로 기록 켜기. 기본값 켜짐. */
    fun autoStart(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(AUTO_START, true)

    fun setAutoStart(c: Context, on: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(AUTO_START, on).apply()
}
