package ai.symptomnote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pleos UI 가이드 원칙: 큰 터치 영역(버튼 최소 높이 72dp), 높은 대비(검정 바탕·흰 글자·노란 강조), 최소 글자 20sp.
 */
object C {
    val Yellow = Color(0xFFFFD600)
    val Bg = Color.Black
    val Panel = Color(0xFF1C1C1C)
    val Line = Color(0xFF3A3A3A)
    val Text = Color.White
    val Sub = Color(0xFFBDBDBD)
    val Red = Color(0xFFFF6E6E)
    val Green = Color(0xFF7CE38B)
    val Blue = Color(0xFF8CC8FF)
}

private val typo = Typography(
    headlineMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 24.sp),
    bodyMedium = TextStyle(fontSize = 22.sp),
    bodySmall = TextStyle(fontSize = 20.sp),
    labelLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
)

@Composable
fun NoteTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = C.Yellow, onPrimary = Color.Black, background = C.Bg, onBackground = C.Text,
        surface = C.Panel, onSurface = C.Text, secondary = C.Blue, error = C.Red
    ),
    typography = typo,
    content = content
)

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val m = modifier.heightIn(min = 76.dp)
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = m, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Yellow, contentColor = Color.Black,
            disabledContainerColor = Color(0xFF424242), disabledContentColor = C.Sub)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text, disabledContentColor = C.Sub)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, color = C.Text)

@Composable
fun Body(text: String, color: Color = C.Text, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier)

@Composable
fun Small(text: String, color: Color = C.Sub) = Text(text, style = MaterialTheme.typography.bodySmall, color = color)

/** 위쪽 막대: 뒤로 + 제목 + 오른쪽 동작들. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onBack != null) BigButton("← 뒤로", primary = false, onClick = onBack)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = C.Text, modifier = Modifier.weight(1f))
        actions()
    }

/** 시연 데이터가 들어 있음을 알리는 띠. */
@Composable
fun DemoBanner() = Box(Modifier.fillMaxWidth().background(Color(0xFF3D2E00), RoundedCornerShape(10.dp)).padding(12.dp)) {
    Text("시연 데이터가 포함되어 있어요 (설정에서 지울 수 있음)", color = C.Yellow, style = MaterialTheme.typography.bodyMedium)
}

/** 간단한 가로 막대(0~1). */
@Composable
fun Bar(fraction: Double, color: Color, modifier: Modifier = Modifier) =
    Box(modifier.height(26.dp).background(C.Line, RoundedCornerShape(6.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).height(26.dp).background(color, RoundedCornerShape(6.dp)))
    }

@Composable
fun Gap(w: Int = 0, h: Int = 0) = Box(Modifier.width(w.dp).height(h.dp))

fun fmtTime(t: Long) = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(t))
fun fmtDate(t: Long) = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(t))
fun pct(v: Double) = "${Math.round(v * 100)}%"
