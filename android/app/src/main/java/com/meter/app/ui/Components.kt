package com.meter.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.meter.app.core.Source
import com.meter.app.ui.theme.MeterColors
import com.meter.app.ui.theme.MeterDims

/*
 * The visual vocabulary of the Meter design, in one file:
 * card, uppercase label, ring meter, pills, chips, stat rows, stacked bar, buttons, steppers,
 * switch and threshold rows. Both screens are assembled from these, which is what keeps the
 * Setup page looking like it was drawn by the same hand as the Meter page.
 */

@Composable
fun MeterCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MeterDims.RADIUS.dp))
            .background(MeterColors.Card)
            .border(1.dp, MeterColors.CardLine, RoundedCornerShape(MeterDims.RADIUS.dp))
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun CardLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = MeterColors.Ink5
    )
}

// ------------------------------------------------------------------ ring meter

/**
 * The 73 % ring: a 12.5 dp stroke on a 122 dp circle, starting at the top and running clockwise,
 * with the percentage inside. Colour shifts to amber near the limit and red once the source is
 * blocked, which is the only visual change the design needs for the blocked state.
 */
@Composable
fun RingMeter(percent: Float, modifier: Modifier = Modifier) {
    val clamped = percent.coerceIn(0f, 100f)
    val color = when {
        percent >= 100f -> MeterColors.Red
        percent >= 90f -> MeterColors.Amber
        else -> MeterColors.Green
    }
    Box(modifier = modifier.size(MeterDims.RING.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(MeterDims.RING.dp)) {
            val stroke = MeterDims.RING_STROKE.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = MeterColors.Track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            if (clamped > 0.4f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * (clamped / 100f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${percent.toInt()}%",
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                color = color
            )
            Text(
                text = "used",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MeterColors.Ink5
            )
        }
    }
}

// ------------------------------------------------------------------------ pills

@Composable
fun FamilyPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) MeterColors.GreenDim else Color.Transparent)
            .border(
                1.dp,
                if (selected) MeterColors.Green else MeterColors.LineSoft,
                RoundedCornerShape(15.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.2).sp,
            color = if (selected) MeterColors.Green else MeterColors.Ink2
        )
    }
}

/** The dashed "long-press for settings" pill — it is also a shortcut into Setup. */
@Composable
fun HintPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .drawBehind {
                drawRoundRect(
                    color = MeterColors.Ink6,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(15.dp.toPx())
                )
            }
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            letterSpacing = (-0.2).sp,
            color = MeterColors.Ink6
        )
    }
}

/** One chip per source inside the selected family: "Vodafone · SIM 1". */
@Composable
fun SourceChip(source: Source, selected: Boolean, blocked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MeterColors.Card)
            .border(
                1.dp,
                when {
                    blocked -> MeterColors.RedLine
                    selected -> Color(0xFF3B3B3B)
                    else -> MeterColors.CardLine
                },
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        blocked -> MeterColors.Red
                        selected -> MeterColors.Green
                        else -> MeterColors.Ink6
                    }
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = source.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (blocked) MeterColors.Red else if (selected) MeterColors.Ink1 else MeterColors.Ink3
        )
        Spacer(Modifier.width(5.dp))
        Text(text = source.detail, fontSize = 12.sp, color = MeterColors.Ink6)
        if (blocked) {
            Spacer(Modifier.width(5.dp))
            Text(text = "· blocked", fontSize = 12.sp, color = MeterColors.Red)
        }
    }
}

// ------------------------------------------------------------------------- rows

@Composable
fun StatRow(key: String, value: String, hot: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(23.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 13.sp, color = MeterColors.Ink3)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.sp,
            color = if (hot) MeterColors.Red else MeterColors.Ink1
        )
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = MeterColors.Ink1) {
    Row(
        modifier = Modifier.fillMaxWidth().height(23.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 13.sp, color = MeterColors.Ink3)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

/** Green/blue proportion bar used by Breakdown. */
@Composable
fun StackedBar(greenFraction: Float, blueFraction: Float, showBlue: Boolean) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(MeterDims.BAR.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        drawRect(color = MeterColors.Track)
        val g = (size.width * greenFraction.coerceIn(0f, 1f))
        val b = (size.width * blueFraction.coerceIn(0f, 1f))
        if (g > 0f) drawRect(color = MeterColors.Green, size = Size(g, size.height))
        if (showBlue && b > 0f) {
            drawRect(
                color = MeterColors.Blue,
                topLeft = Offset(g, 0f),
                size = Size(b.coerceAtMost(size.width - g), size.height)
            )
        }
    }
}

@Composable
fun LegendSquare(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.5.sp, color = MeterColors.Ink4)
    }
}

// ---------------------------------------------------------------------- buttons

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(MeterColors.Green)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MeterColors.Bg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MeterColors.Bg)
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val border = if (danger) MeterColors.RedLine else MeterColors.LineSoft
    val fg = if (danger) MeterColors.Red else MeterColors.Ink2
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (danger) MeterColors.RedDim else Color.Transparent)
            .border(1.dp, border, RoundedCornerShape(19.dp))
            .clickable { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFF1A1A1A))
            .border(1.dp, MeterColors.LineSoft, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 20.sp, color = MeterColors.Ink1)
    }
}

@Composable
fun SmallStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFF1A1A1A))
            .border(1.dp, MeterColors.LineSoft, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 16.sp, color = MeterColors.Ink1)
    }
}

@Composable
fun MeterSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 25.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) MeterColors.GreenDim else Color(0xFF232323))
            .border(
                1.dp,
                if (checked) MeterColors.Green else MeterColors.LineSoft,
                RoundedCornerShape(13.dp)
            )
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(19.dp)
                .clip(CircleShape)
                .background(if (checked) MeterColors.Green else Color(0xFF6B6B6B))
        )
    }
}

/** Auto / MB / GB selector. */
@Composable
fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (on) MeterColors.GreenDim else Color.Transparent)
                    .border(
                        1.dp,
                        if (on) MeterColors.Green else MeterColors.LineSoft,
                        RoundedCornerShape(17.dp)
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) MeterColors.Green else MeterColors.Ink3
                )
            }
        }
    }
}

/** Small editable "%" box used by the notification thresholds. */
@Composable
fun PercentField(value: String, stop: Boolean, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF191919))
            .border(
                1.dp,
                if (stop) MeterColors.RedLine else MeterColors.LineSoft,
                RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (stop) MeterColors.Red else MeterColors.Ink1,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The tall thin ↓ / ↑ arrow used by Live Speed. */
@Composable
fun SpeedArrow(up: Boolean, color: Color = MeterColors.Green) {
    Canvas(modifier = Modifier.size(width = 12.dp, height = 18.dp)) {
        val cx = size.width / 2f
        val stroke = 1.8.dp.toPx()
        val top = 2.dp.toPx()
        val bottom = size.height - 2.dp.toPx()
        val head = 4.5.dp.toPx()
        if (!up) {
            drawLine(color, Offset(cx, top), Offset(cx, bottom), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx - head, bottom - head), Offset(cx, bottom), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx + head, bottom - head), Offset(cx, bottom), stroke, StrokeCap.Round)
        } else {
            drawLine(color, Offset(cx, bottom), Offset(cx, top), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx - head, top + head), Offset(cx, top), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx + head, top + head), Offset(cx, top), stroke, StrokeCap.Round)
        }
    }
}

/** Long-press detector used by the top bar, wired by the screens. */
fun Modifier.longPressAndTap(onTap: () -> Unit, onLongPress: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
    }

@Composable
fun IconAction(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** Shared icon shortcuts so screens stay readable. */
object MeterIcons {
    val Back = Icons.Filled.ArrowBack
    val Reset = Icons.Filled.Refresh
    val Warning = Icons.Filled.Warning
    val Add = Icons.Filled.Add
    val Delete = Icons.Filled.Delete
}
