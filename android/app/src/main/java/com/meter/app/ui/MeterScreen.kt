package com.meter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meter.app.core.FamilyFilter
import com.meter.app.core.MeterSnapshot
import com.meter.app.core.QuotaEngine
import com.meter.app.core.Source
import com.meter.app.core.SourceType
import com.meter.app.core.Units
import com.meter.app.ui.theme.MeterColors
import com.meter.app.ui.theme.MeterDims

/**
 * PAGE 1 — METER.
 *
 * The uploaded design, reproduced block by block: Usage (ring + four stats), Live Speed,
 * Breakdown, Usage Cycle with Reset, and Sanity Check. Everything shown belongs to the source —
 * or the whole family — that is currently selected; nothing on this page is a global total that
 * mixes SIM and Wi-Fi traffic together unless "All" is chosen.
 */
@Composable
fun MeterScreen(
    state: MeterSnapshot,
    onFilter: (FamilyFilter) -> Unit,
    onSelect: (String) -> Unit,
    onReset: (List<String>) -> Unit,
    onOpenSetup: () -> Unit
) {
    val scope = scopeOf(state)
    val unit = scope.unit
    val blocked = scope.blocked
    val usedText = Units.format(scope.used, unit)
    val quotaText = if (scope.quota > 0) Units.format(scope.quota, unit) else "Unlimited"
    val leftText = if (scope.quota > 0) Units.format(scope.left, unit) else "—"
    val dailyText = Units.format(scope.dailyAverage, com.meter.app.core.UnitMode.MB) + "/d"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = MeterDims.SCREEN_PAD.dp, end = MeterDims.SCREEN_PAD.dp, top = 2.dp, bottom = 33.dp)
    ) {
        // ----------------------------------------------------------- top bar
        MeterCard(
            modifier = Modifier.height(60.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .longPressAndTap(onTap = {}, onLongPress = onOpenSetup),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                FamilyPill("All", state.filter == FamilyFilter.ALL) { onFilter(FamilyFilter.ALL) }
                FamilyPill("SIM", state.filter == FamilyFilter.SIM) { onFilter(FamilyFilter.SIM) }
                FamilyPill("Wi-Fi", state.filter == FamilyFilter.WIFI) { onFilter(FamilyFilter.WIFI) }
                Spacer(Modifier.weight(1f))
                HintPill("long-press for settings", onOpenSetup)
            }
        }

        // ------------------------------------------ the sources inside the family
        val family = com.meter.app.core.Selection.visible(state)
        if (state.filter != FamilyFilter.ALL && family.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                family.forEach { source ->
                    val usage = state.usageOf(source.id)
                    SourceChip(
                        source = source,
                        selected = state.selectedIds.firstOrNull() == source.id,
                        blocked = usage.blocked
                    ) { onSelect(source.id) }
                }
            }
        }

        // --------------------------------------------------------------- usage
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard(modifier = Modifier.heightIn(min = if (blocked && scope.quota > 0) 250.dp else 198.dp)) {
            CardLabel("Usage")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(138.dp), contentAlignment = Alignment.Center) {
                    RingMeter(scope.percent)
                }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    StatRow("Used", usedText)
                    StatRow("Quota", quotaText)
                    StatRow("Left", leftText, hot = blocked && scope.quota > 0)
                    StatRow("Daily avg", dailyText)
                }
            }
            if (blocked && scope.quota > 0) {
                Spacer(Modifier.height(10.dp))
                BlockBanner(
                    text = "${scope.label} is blocked — its quota for this cycle is spent. " +
                        "Traffic on this source is stopped; every other source keeps working. " +
                        "Reset the cycle below, raise the quota in Setup, or switch to another source."
                )
            }
        }

        // ---------------------------------------------------------- live speed
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard(modifier = Modifier.heightIn(min = 119.dp)) {
            CardLabel("Live Speed")
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeedValue(state.downBytesPerSec, up = false)
                SpeedValue(state.upBytesPerSec, up = true)
            }
            Spacer(Modifier.height(10.dp))
            val live = state.sourceOf(state.liveSourceId)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    live == null -> MeterColors.Ink6
                                    state.usageOf(live.id).blocked -> MeterColors.Red
                                    else -> MeterColors.Green
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            live == null -> "Not connected"
                            live.type == SourceType.WIFI -> "Connected to Wi-Fi"
                            else -> "Connected to Mobile data"
                        },
                        fontSize = 13.sp,
                        letterSpacing = 0.3.sp,
                        color = MeterColors.Ink2
                    )
                }
                Text(
                    text = live?.name ?: "offline",
                    fontSize = 10.5.sp,
                    color = MeterColors.Ink7,
                    modifier = Modifier.width(170.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 1
                )
            }
        }

        // ---------------------------------------------------------- breakdown
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard(modifier = Modifier.heightIn(min = 88.dp)) {
            CardLabel("Breakdown")
            Spacer(Modifier.height(14.dp))
            BreakdownBody(state, scope.rx)
        }

        // -------------------------------------------------------- usage cycle
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard(modifier = Modifier.heightIn(min = 154.dp)) {
            CardLabel("Usage Cycle")
            Spacer(Modifier.height(14.dp))
            KeyValueRow("Started", Units.formatDateTime(scope.cycleStart))
            KeyValueRow("Ends", Units.formatDateTime(scope.cycleEnd))
            Spacer(Modifier.height(11.dp))
            PrimaryButton(text = "Reset", icon = MeterIcons.Reset) { onReset(scope.ids) }
        }

        // ------------------------------------------------------ sanity check
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 12.dp)) {
            Text(
                text = "Sanity Check",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MeterColors.Ink7
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = sanityText(state, scope.ids),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MeterColors.Ink7
            )
        }
    }
}

@Composable
private fun SpeedValue(bytesPerSecond: Long, up: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpeedArrow(up = up)
        Spacer(Modifier.width(3.dp))
        Text(
            text = Units.formatRate(bytesPerSecond),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = MeterColors.Green
        )
    }
}

@Composable
private fun BlockBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MeterColors.RedDim)
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        androidx.compose.material3.Icon(
            MeterIcons.Warning, contentDescription = null,
            tint = Color(0xFFFF8F8F), modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFFFF8F8F))
    }
}

/**
 * Breakdown follows what is selected: "All" shows how the total splits between SIM and Wi-Fi —
 * which is the design's bar — while a single source shows its own download/upload split, because
 * that is the only breakdown that means anything for one radio.
 */
@Composable
private fun BreakdownBody(state: MeterSnapshot, sourceRx: Long) {
    val aggregate = state.isAggregate
    val a = if (aggregate) state.visible.filter { it.type == SourceType.SIM }
        .sumOf { state.usageOf(it.id).total } else sourceRx
    val b = if (aggregate) state.visible.filter { it.type == SourceType.WIFI }
        .sumOf { state.usageOf(it.id).total } else {
        state.selectedIds.sumOf { state.usageOf(it).total } - sourceRx
    }
    val total = (a + b).coerceAtLeast(1L).toFloat()
    val labelA = if (aggregate) "SIM" else "Down"
    val labelB = if (aggregate) "Wi-Fi" else "Up"
    val unit = state.configOf(state.selectedIds.firstOrNull() ?: "").unit

    Column {
        StackedBar(greenFraction = a / total, blueFraction = b / total, showBlue = true)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(17.dp)) {
            LegendSquare(MeterColors.Green, "$labelA ${Units.format(a.toLong(), unit)}")
            LegendSquare(MeterColors.Blue, "$labelB ${Units.format(b.toLong(), unit)}")
        }
    }
}

private fun sanityText(state: MeterSnapshot, ids: List<String>): String {
    val source = state.sourceOf(ids.firstOrNull())
    return when {
        source?.type == SourceType.SIM ->
            "Compare the number above with your carrier's own counter (operator app or USSD balance). " +
                "They should agree within a percent or two. If they don't, the operator may be billing " +
                "the tunnelling overhead — adjust the per-packet overhead in Setup."
        source?.type == SourceType.WIFI ->
            "Compare the number above with the router's own counter. They should agree within a percent " +
                "or two. If they don't, the router may be counting 802.11 retries or LAN traffic that " +
                "never leaves your home."
        else ->
            "Compare the number above with your router's own counter. They should agree within a percent " +
                "or two. If they don't, the OS may be counting system updates or VPN tunnels."
    }
}

/** The Meter always renders one scope: a single source, or the whole current family. */
private fun scopeOf(state: MeterSnapshot) = QuotaEngine.scope(
    ids = state.selectedIds,
    label = state.selectedLabel,
    sources = state.sources.associateBy { it.id },
    configs = state.configs,
    usages = state.usage,
    now = System.currentTimeMillis()
)

/** Small helper used by the Setup header as well. */
fun sourceLabelOf(state: MeterSnapshot, id: String?): Pair<Source?, String> {
    val source = state.sourceOf(id)
    return source to (source?.name ?: "All sources")
}
