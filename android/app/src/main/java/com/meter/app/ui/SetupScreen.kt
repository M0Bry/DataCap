package com.meter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meter.app.core.MeterSnapshot
import com.meter.app.core.QuotaSteps
import com.meter.app.core.SampleIntervals
import com.meter.app.core.SourceConfig
import com.meter.app.core.SourceType
import com.meter.app.core.ThresholdList
import com.meter.app.core.UnitMode
import com.meter.app.core.Units
import com.meter.app.ui.theme.MeterColors
import com.meter.app.ui.theme.MeterDims

/**
 * PAGE 2 — SETUP.
 *
 * Every control belongs to *one* source: the one named in the header, which is the same selection
 * the Meter page is showing. Changing Vodafone's quota can never touch Orange, and a Wi-Fi
 * network's cycle is its own — that is the single most important rule of the app, and the reason
 * this page has a source switcher at the top instead of global settings.
 *
 * The Usage Cycle card is read-only apart from the cycle *length*: the cycle itself is started and
 * restarted from the Meter page, which owns the Reset action.
 */
@Composable
fun SetupScreen(
    state: MeterSnapshot,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onConfig: (String, (SourceConfig) -> SourceConfig) -> Unit,
    onClear: (String) -> Unit,
    onManualBlock: (String, Boolean) -> Unit,
    onPreviewAlert: (String, Int) -> Unit,
    onPause: (Boolean) -> Unit,
    onKillSwitch: (Boolean) -> Unit,
    onSampleMillis: (Long) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestPhoneState: () -> Unit,
    onRequestVpn: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    val source = state.sourceOf(state.selectedIds.firstOrNull())
    val config = state.configOf(source?.id ?: "")
    val usage = state.usageOf(source?.id ?: "")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = MeterDims.SCREEN_PAD.dp, end = MeterDims.SCREEN_PAD.dp, top = 2.dp, bottom = 33.dp)
    ) {
        // ------------------------------------------------------------ header
        MeterCard(
            modifier = Modifier.height(60.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(MeterIcons.Back, MeterColors.Ink2, onBack)
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SETUP",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                        color = MeterColors.Ink5
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (usage.blocked) MeterColors.Red else MeterColors.Green)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            source?.name ?: "No source",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MeterColors.Ink1
                        )
                    }
                }
                Text(source?.iface.orEmpty(), fontSize = 10.5.sp, color = MeterColors.Ink6)
            }
        }

        // --------------------------------------------------- the source list
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.sources.forEach { item ->
                SourceChip(
                    source = item,
                    selected = item.id == source?.id,
                    blocked = state.usageOf(item.id).blocked
                ) { onSelect(item.id) }
            }
        }

        if (source == null) {
            Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
            MeterCard {
                CardLabel("No source yet")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Meter has not discovered a SIM or a Wi-Fi network yet. Connect once and the " +
                        "source will appear here with its own quota, cycle and thresholds.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MeterColors.Ink6
                )
            }
            return@Column
        }

        // -------------------------------------------------------- data quota
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("Data Quota")
            Spacer(Modifier.height(16.dp))
            val parts = Units.quotaParts(config.quotaBytes, config.unit)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepButton("−") {
                    onConfig(source.id) { it.copy(quotaBytes = QuotaSteps.step(it.quotaBytes, -1)) }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        parts.first,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        color = MeterColors.Green
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        parts.second,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MeterColors.Green,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                StepButton("+") {
                    onConfig(source.id) { it.copy(quotaBytes = QuotaSteps.step(it.quotaBytes, 1)) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                QuotaSteps.PRESETS.take(6).forEach { preset ->
                    PresetChip(preset.first, QuotaSteps.isActive(config.quotaBytes, preset)) {
                        onConfig(source.id) { it.copy(quotaBytes = preset.second) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                QuotaSteps.PRESETS.drop(6).forEach { preset ->
                    PresetChip(preset.first, QuotaSteps.isActive(config.quotaBytes, preset)) {
                        onConfig(source.id) { it.copy(quotaBytes = preset.second) }
                    }
                }
            }

            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(15.dp))
            KeyValueRow(
                "Used this cycle",
                Units.format(usage.total, config.unit) +
                    if (config.quotaBytes > 0) "  ·  ${usage.total * 100 / config.quotaBytes}%" else ""
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Block this source at 100 %",
                subtitle = "Only this source is cut. Others stay online.",
                checked = config.blockAtQuota
            ) { onConfig(source.id) { c -> c.copy(blockAtQuota = it) } }
            Spacer(Modifier.height(6.dp))
            StepperRow(
                title = "Per-packet overhead",
                subtitle = "Framing the operator bills on top of IP (36 B mobile, 24 B Wi-Fi).",
                value = "${config.overheadBytes} B",
                onMinus = { onConfig(source.id) { c -> c.copy(overheadBytes = (c.overheadBytes - 4).coerceAtLeast(0)) } },
                onPlus = { onConfig(source.id) { c -> c.copy(overheadBytes = (c.overheadBytes + 4).coerceAtMost(200)) } }
            )
        }

        // ------------------------------------------------------- usage cycle
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("Usage Cycle")
            Spacer(Modifier.height(16.dp))
            KeyValueRow("Starts", Units.formatDateTime(usage.cycleStart))
            Spacer(Modifier.height(10.dp))
            StepperRow(
                title = "Length",
                subtitle = "Days — the cycle rolls over on its own.",
                value = "${config.periodDays} d",
                onMinus = { onConfig(source.id) { c -> c.copy(periodDays = (c.periodDays - 1).coerceAtLeast(1)) } },
                onPlus = { onConfig(source.id) { c -> c.copy(periodDays = (c.periodDays + 1).coerceAtMost(365)) } }
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow(
                "Ends",
                Units.formatDateTime(
                    if (usage.cycleStart <= 0L) System.currentTimeMillis()
                    else usage.cycleStart + config.periodDays * Units.DAY
                )
            )
            Spacer(Modifier.height(13.dp))
            Text(
                "The cycle is started and restarted from the Meter page, where the Reset button " +
                    "belongs to the source you selected there.",
                fontSize = 12.sp, lineHeight = 17.sp, color = MeterColors.Ink6
            )
        }

        // ----------------------------------------------------- notifications
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("Notifications")
            Spacer(Modifier.height(4.dp))
            config.thresholds.forEachIndexed { index, threshold ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PercentField(value = "$threshold%", stop = threshold >= 100) { raw ->
                        val digits = raw.filter { it.isDigit() }.take(3)
                        val value = digits.toIntOrNull() ?: return@PercentField
                        onConfig(source.id) { c -> c.copy(thresholds = ThresholdList.update(c.thresholds, index, value)) }
                    }
                    Spacer(Modifier.width(9.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MeterColors.LineSoft, RoundedCornerShape(10.dp))
                            .clickable { onPreviewAlert(source.id, threshold) }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = if (threshold >= 100) "Quota exhausted — source blocked"
                            else "Quota is $threshold% used",
                            fontSize = 12.sp,
                            color = MeterColors.Ink3,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    val canDelete = config.thresholds.size > 1
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF2C1C1C), RoundedCornerShape(10.dp))
                            .clickable(enabled = canDelete) {
                                onConfig(source.id) { c -> c.copy(thresholds = ThresholdList.remove(c.thresholds, index)) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete, contentDescription = "Remove threshold",
                            tint = if (canDelete) Color(0xFFAA5555) else Color(0xFF3A2A2A),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val canAdd = ThresholdList.add(config.thresholds) != null
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(19.dp))
                    .clickable(enabled = canAdd) {
                        onConfig(source.id) { c ->
                            ThresholdList.add(c.thresholds)?.let { c.copy(thresholds = it) } ?: c
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Add, contentDescription = null,
                        tint = if (canAdd) MeterColors.Ink3 else MeterColors.Ink6,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (canAdd) "Add threshold" else "Threshold list is full",
                        fontSize = 12.5.sp,
                        color = if (canAdd) MeterColors.Ink3 else MeterColors.Ink6
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "Delivered by the background service, so they still arrive with the app closed. " +
                    "A 100 % threshold also triggers the block for this source.",
                fontSize = 12.sp, lineHeight = 17.sp, color = MeterColors.Ink6
            )
        }

        // ------------------------------------------------------ display unit
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("Data Display Unit")
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = listOf(
                    UnitMode.AUTO.name to "Auto",
                    UnitMode.MB.name to "MB",
                    UnitMode.GB.name to "GB"
                ),
                selected = config.unit.name
            ) { value ->
                onConfig(source.id) { c -> c.copy(unit = UnitMode.valueOf(value)) }
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "Auto behaves like the Meter design: MiB below 1 GiB, GiB above (1024-based).",
                fontSize = 12.sp, lineHeight = 17.sp, color = MeterColors.Ink6
            )
            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(15.dp))
            KeyValueRow(
                "Preview",
                Units.format(usage.total, config.unit) + " / " +
                    if (config.quotaBytes > 0) Units.format(config.quotaBytes, config.unit) else "∞"
            )
        }

        // ------------------------------------------------------- this source
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("This Source")
            Spacer(Modifier.height(16.dp))
            KeyValueRow(
                "Kind",
                if (source.type == SourceType.SIM) "Mobile data · ${source.detail}" else "Wi-Fi network"
            )
            if (source.carrier.isNotBlank()) KeyValueRow("Carrier", source.carrier)
            KeyValueRow("Interface", source.iface)
            KeyValueRow("Cycle usage", Units.format(usage.total, config.unit))
            if (usage.reconciledBytes > 0) {
                KeyValueRow(
                    "Recovered by the system ledger",
                    "+" + Units.format(usage.reconciledBytes, config.unit),
                    valueColor = MeterColors.Ink2
                )
            }
            if (usage.platformTotal > 0) {
                val driftText = if (usage.drift > 0) {
                    "we are ${Units.format(usage.drift, config.unit)} behind"
                } else {
                    "in step"
                }
                KeyValueRow("Platform counter", "$driftText", valueColor = MeterColors.Ink3)
            }
            KeyValueRow(
                "Status",
                when {
                    usage.overQuota && state.killSwitchActive -> "Blocked — quota spent (valve closed)"
                    usage.overQuota -> "Blocked — quota spent"
                    usage.manualBlock -> "Blocked manually"
                    else -> "Active"
                },
                valueColor = if (usage.blocked) MeterColors.Red else MeterColors.Green
            )
            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(15.dp))
            if (usage.blocked) {
                GhostButton(text = "Unblock this source") { onManualBlock(source.id, false) }
            } else {
                GhostButton(text = "Block this source only", danger = true) {
                    onManualBlock(source.id, true)
                }
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(text = "Clear counters & alerts") { onClear(source.id) }
        }

        // -------------------------------------------------------- monitoring
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("Monitoring")
            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "Count while the app is closed",
                subtitle = "Small foreground service; no wake locks, no GPS.",
                checked = state.serviceRunning
            ) {
                if (it) onStartService() else onStopService()
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Pause metering",
                subtitle = "Stops counting without losing anything already measured.",
                checked = state.meteringPaused
            ) { onPause(it) }
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Block an exhausted SIM with a local VPN",
                subtitle = "Only while that SIM is the active connection; released on Wi-Fi.",
                checked = state.killSwitchEnabled
            ) { onKillSwitch(it) }
            Spacer(Modifier.height(14.dp))
            Text("Sample interval", fontSize = 13.sp, color = MeterColors.Ink2)
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = SampleIntervals.OPTIONS.map { it.second.toString() to it.first },
                selected = SampleIntervals.sanitize(state.sampleMillis).toString()
            ) { onSampleMillis(it.toLong()) }
            Spacer(Modifier.height(13.dp))
            Text(
                "Shorter intervals narrow the window in which two Wi-Fi networks could share a " +
                    "sample (all Wi-Fi traffic passes through one interface, so it is credited to " +
                    "the SSID that is associated at sample time). " +
                    if (state.perSimInterfaces) {
                        "This phone exposes one interface per SIM, so SIM 1 and SIM 2 are counted " +
                            "exactly, straight from the kernel."
                    } else {
                        "This phone shares one interface between the SIMs, so bytes are credited to " +
                            "the subscription that is the default data SIM at sample time — " +
                            "switching SIM re-bases the counters, so nothing is ever mixed."
                    },
                fontSize = 12.sp, lineHeight = 17.sp, color = MeterColors.Ink6
            )
        }

        // ------------------------------------------------------- permissions
        Spacer(Modifier.height(MeterDims.CARD_GAP.dp))
        MeterCard {
            CardLabel("Permissions")
            Spacer(Modifier.height(6.dp))
            PermissionRow(
                title = "Notifications",
                detail = "Threshold alerts",
                granted = true,
                actionLabel = "Allow"
            ) { onRequestNotifications() }
            PermissionRow(
                title = "Usage access",
                detail = "Exact per-SIM totals from the system ledger",
                granted = state.usageAccessGranted,
                actionLabel = "Grant"
            ) { onRequestUsageAccess() }
            PermissionRow(
                title = "Wi-Fi names",
                detail = "Splits the Wi-Fi interface per SSID",
                granted = state.locationGranted,
                actionLabel = "Grant"
            ) { onRequestLocation() }
            PermissionRow(
                title = "SIM names",
                detail = "Carrier and subscription per SIM card",
                granted = state.phoneStateGranted,
                actionLabel = "Grant"
            ) { onRequestPhoneState() }
            PermissionRow(
                title = "Block SIM over quota",
                detail = "Optional local-VPN valve",
                granted = state.vpnPermissionGranted,
                actionLabel = "Enable"
            ) { onRequestVpn() }
            PermissionRow(
                title = "Ignore battery optimisation",
                detail = "Keeps counting on aggressive ROMs",
                granted = false,
                actionLabel = "Open"
            ) { onRequestBatteryExemption() }
            Spacer(Modifier.height(13.dp))
            Text(
                "Without a permission the app keeps working and simply shows less detail — nothing " +
                    "stops being counted. Usage access is what lets the meter check its own figures " +
                    "against the system's per-SIM ledger and recover anything it could not see.",
                fontSize = 12.sp, lineHeight = 17.sp, color = MeterColors.Ink6
            )
        }
    }
}

// ------------------------------------------------------------------ sub-parts

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF1D1D1D))
    )
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) MeterColors.GreenDim else Color(0xFF191919))
            .border(
                1.dp,
                if (selected) MeterColors.Green else MeterColors.LineSoft,
                RoundedCornerShape(15.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) MeterColors.Green else MeterColors.Ink3
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MeterColors.Ink2)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.5.sp, lineHeight = 15.sp, color = MeterColors.Ink6)
        }
        Spacer(Modifier.width(14.dp))
        MeterSwitch(checked = checked, onChange = onChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MeterColors.Ink2)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.5.sp, lineHeight = 15.sp, color = MeterColors.Ink6)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallStepButton("−", onMinus)
            Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MeterColors.Ink1)
            }
            SmallStepButton("+", onPlus)
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MeterColors.Ink2)
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 11.5.sp, color = MeterColors.Ink6)
        }
        if (granted) {
            Text("Granted", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MeterColors.Green)
        } else {
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .border(1.dp, MeterColors.LineSoft, RoundedCornerShape(15.dp))
                    .clickable { onClick() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(actionLabel, fontSize = 12.sp, color = MeterColors.Ink2)
            }
        }
    }
}
