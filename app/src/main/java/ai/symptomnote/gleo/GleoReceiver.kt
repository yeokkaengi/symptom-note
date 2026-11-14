package ai.symptomnote.gleo

import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.audio.TAG
import ai.symptomnote.core.Conditions
import ai.symptomnote.data.AppDb
import ai.symptomnote.service.SymptomAnalyzer
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 글레오 함수(assets/schema.json, component "service").
 * - 글레오는 sendBroadcast(action = "<패키지>.<함수 이름>", package = 이 앱) 으로 부른다(verify 5-16 실측).
 *   문서 예제 형태(함수 이름만)도 함께 받는다.
 * - 20초 안에 APP_ACTION_RESPONSE 를 보내야 한다(verify 5-15: 20초 뒤 시간 초과 안내). 여기서는 10초 안에 계산을 끊는다.
 * - 녹음(마이크) 중에는 글레오가 호출 자체를 무시한다(verify 5-19, 5-23). 그래서 녹음을 시작하는 함수는 만들지 않고,
 *   녹음이 꺼져 있을 때 쓰는 조회용 함수만 둔다. 자세한 내용: docs/platform-findings.md
 */
class GleoReceiver : BroadcastReceiver() {
    companion object {
        const val SUMMARY = "READ_SYMPTOM_SUMMARY"
        const val HOW_TO = "HOW_TO_START_RECORDING"
        const val EXTRA_PARAM = "ACTION_PARAM"
        const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

        /** "<패키지>.<함수>" 와 "<함수>" 두 형태 모두 함수 이름으로. 모르는 action 이면 null. */
        fun functionOf(action: String?, pkg: String): String? =
            action?.removePrefix("$pkg.")?.takeIf { it == SUMMARY || it == HOW_TO }

        fun howTo(recording: Boolean) = if (recording)
            "지금 증상노트가 기록 중이에요. 소리가 나면 화면의 큰 노란 버튼 '방금 소리 기록'을 누르세요. 기록 중에는 글레오로 부를 수 없어요."
        else "차를 세우고 기어를 P로 둔 뒤 증상노트 홈에서 '기록 시작'을 누르세요. 그다음 소리가 나면 큰 노란 버튼 '방금 소리 기록'을 누르면 돼요."

        suspend fun summary(c: Context): String {
            val dao = AppDb.get(c).dao()
            val symptoms = dao.symptomsNow()
            if (symptoms.isEmpty()) return "아직 정리된 증상이 없어요. 기록을 모은 뒤 주차 화면에서 증상으로 묶어 주세요."
            val s = symptoms.first()
            val info = SymptomAnalyzer.load(c, s, dao.recordingsOfNow(s.id))
            return buildString {
                append("증상 ${symptoms.size}개가 있어요. 최근 증상은 ${s.name}, 기록 ${info.recs.size}회예요. ")
                if (info.rule.isNotEmpty()) append("${Conditions.describe(info.rule)}일 때 자주 들려요. ")
                info.repair?.let { append("수리 뒤 같은 조건을 ${it.passes}번 지났고 기록은 ${it.afterTotal}개예요.") }
            }.trim()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fn = functionOf(intent.action, context.packageName) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "글레오 호출 action=${intent.action} param=${intent.getStringExtra(EXTRA_PARAM)}")
                val msg = when (fn) {
                    SUMMARY -> withTimeoutOrNull(10_000) { summary(context) } ?: "요약을 만드는 데 시간이 걸려요. 주차 화면에서 확인해 주세요."
                    else -> howTo(RingRecorder.isRunning)
                }
                val resp = JSONObject().put("functionName", fn).put("status", "success").put("message", msg)
                context.sendBroadcast(Intent(ACTION_RESPONSE).putExtra("packageName", context.packageName).putExtra("response", resp.toString()))
                Log.i(TAG, "APP_ACTION_RESPONSE $resp")
            } catch (e: Throwable) {
                Log.w(TAG, "글레오 처리 실패: $e")
                val resp = JSONObject().put("functionName", fn).put("status", "fail").put("message", "증상노트에서 처리하지 못했어요")
                context.sendBroadcast(Intent(ACTION_RESPONSE).putExtra("packageName", context.packageName).putExtra("response", resp.toString()))
            } finally {
                pending.finish()
            }
        }
    }
}
