package com.shatyuka.zhiliao.ui

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shatyuka.zhiliao.Catalog
import com.shatyuka.zhiliao.ItemKind
import com.shatyuka.zhiliao.PrefGroup
import com.shatyuka.zhiliao.PrefItem
import com.shatyuka.zhiliao.R

private data class Pane(
    val key: String,
    val title: String,
    val icon: Int,
)

/**
 * Module settings. Phone width is one scrolling list. Expanded width (tablets, unfolded
 * foldables) keeps a navigation rail so the sections stay reachable without a long scroll.
 *
 * Collapsing large app bars are still tagged [ExperimentalMaterial3Api] in Material3 1.4,
 * which is the stable line. The expressive flexible bars only exist on the 1.5 release candidate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    expanded: Boolean,
    hookValues: Map<String, Boolean>,
    textValues: Map<String, String>,
    eulaAccepted: Boolean,
    unsupportedKeys: Set<String>,
    selfCheckStatus: String?,
    backendLabel: String,
    version: String,
    dynamicColorAvailable: Boolean,
    editingKey: String?,
    editingValue: String,
    onToggle: (String, Boolean) -> Unit,
    onOpenEditor: (String, String) -> Unit,
    onEditingChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onDismissEdit: () -> Unit,
    onAcceptEula: () -> Unit,
    onClean: () -> Unit,
    onQueryStatus: () -> Unit,
    onExport: () -> Unit,
    onRestart: () -> Unit,
    onOpenLink: (String) -> Unit,
    onAuthorClick: () -> Unit,
) {
    val panes = buildList {
        add(Pane("general", "通用", R.drawable.ic_toggle_on))
        Catalog.groups.forEach { group ->
            add(Pane(group.key, group.title, group.items.first().icon))
        }
        add(Pane("maintain", "维护", R.drawable.ic_delete))
        add(Pane("about", "关于", R.drawable.ic_info))
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val safeSelected = selected.coerceIn(0, panes.lastIndex)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val mainOn = hookValues[Catalog.mainSwitch.key] ?: Catalog.mainSwitch.defaultValue
    val gate = eulaAccepted && mainOn

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("知了") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    panes.forEachIndexed { index, pane ->
                        NavigationRailItem(
                            selected = index == safeSelected,
                            onClick = { selected = index },
                            icon = {
                                Icon(
                                    painter = painterResource(pane.icon),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(pane.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    SectionColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 28.dp),
                    ) {
                        item(key = "pane") {
                        PaneBody(
                            pane = panes[safeSelected],
                            gate = gate,
                            eulaAccepted = eulaAccepted,
                            mainOn = mainOn,
                            hookValues = hookValues,
                            textValues = textValues,
                            unsupportedKeys = unsupportedKeys,
                            selfCheckStatus = selfCheckStatus,
                            backendLabel = backendLabel,
                            version = version,
                            dynamicColorAvailable = dynamicColorAvailable,
                            onToggle = onToggle,
                            onOpenEditor = onOpenEditor,
                            onAcceptEula = onAcceptEula,
                            onClean = onClean,
                            onQueryStatus = onQueryStatus,
                            onExport = onExport,
                            onRestart = onRestart,
                            onOpenLink = onOpenLink,
                            onAuthorClick = onAuthorClick,
                        )
                        }
                    }
                }
            }
        } else {
            SectionColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 28.dp,
                ),
            ) {
                if (!eulaAccepted) {
                    item(key = "eula") { EulaCard(onAcceptEula) }
                }
                item(key = "general") {
                    GeneralBlock(
                        eulaAccepted = eulaAccepted,
                        mainOn = mainOn,
                        hookValues = hookValues,
                        dynamicColorAvailable = dynamicColorAvailable,
                        onToggle = onToggle,
                    )
                }
                items(Catalog.groups, key = { it.key }) { group ->
                    GroupBlock(group, gate, hookValues, textValues, unsupportedKeys, onToggle, onOpenEditor)
                }
                item(key = "maintain") { MaintainBlock(onClean) }
                item(key = "about") {
                    AboutBlock(
                        version = version,
                        selfCheckStatus = selfCheckStatus,
                        backendLabel = backendLabel,
                        onAuthorClick = onAuthorClick,
                        onOpenLink = onOpenLink,
                        onQueryStatus = onQueryStatus,
                        onExport = onExport,
                        onRestart = onRestart,
                    )
                }
            }
        }
    }

    if (editingKey != null) {
        EditDialog(
            key = editingKey,
            value = editingValue,
            onValueChange = onEditingChange,
            onSave = onSaveEdit,
            onDismiss = onDismissEdit,
        )
    }
}

@Composable
private fun SectionColumn(
    modifier: Modifier,
    contentPadding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxHeight(),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
private fun PaneBody(
    pane: Pane,
    gate: Boolean,
    eulaAccepted: Boolean,
    mainOn: Boolean,
    hookValues: Map<String, Boolean>,
    textValues: Map<String, String>,
    unsupportedKeys: Set<String>,
    selfCheckStatus: String?,
    backendLabel: String,
    version: String,
    dynamicColorAvailable: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onOpenEditor: (String, String) -> Unit,
    onAcceptEula: () -> Unit,
    onClean: () -> Unit,
    onQueryStatus: () -> Unit,
    onExport: () -> Unit,
    onRestart: () -> Unit,
    onOpenLink: (String) -> Unit,
    onAuthorClick: () -> Unit,
) {
    when (pane.key) {
        "general" -> {
            if (!eulaAccepted) EulaCard(onAcceptEula)
            GeneralBlock(eulaAccepted, mainOn, hookValues, dynamicColorAvailable, onToggle)
        }
        "maintain" -> MaintainBlock(onClean)
        "about" -> AboutBlock(
            version, selfCheckStatus, backendLabel, onAuthorClick, onOpenLink, onQueryStatus, onExport, onRestart,
        )
        else -> {
            val group = Catalog.groups.first { it.key == pane.key }
            GroupBlock(group, gate, hookValues, textValues, unsupportedKeys, onToggle, onOpenEditor)
        }
    }
}

@Composable
private fun GeneralBlock(
    eulaAccepted: Boolean,
    mainOn: Boolean,
    hookValues: Map<String, Boolean>,
    dynamicColorAvailable: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    SectionHeader("通用")
    SettingCard {
        SwitchRow(
            item = Catalog.mainSwitch,
            accent = Accent.Blue,
            checked = hookValues[Catalog.mainSwitch.key] ?: Catalog.mainSwitch.defaultValue,
            enabled = eulaAccepted,
            summaryOverride = null,
            onToggle = onToggle,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val follow = hookValues[Catalog.dynamicColor.key] ?: Catalog.dynamicColor.defaultValue
        SwitchRow(
            item = Catalog.dynamicColor,
            accent = Accent.Blue,
            checked = dynamicColorAvailable && follow,
            enabled = eulaAccepted && dynamicColorAvailable,
            summaryOverride = if (dynamicColorAvailable) {
                Catalog.dynamicColor.summary
            } else {
                "当前系统没有壁纸取色，使用内置配色"
            },
            onToggle = onToggle,
        )
    }
    if (eulaAccepted && !mainOn) {
        Spacer(Modifier.height(12.dp))
        SettingCard {
            Text(
                text = "总开关已关闭，下面的功能都不会生效。",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupBlock(
    group: PrefGroup,
    gate: Boolean,
    hookValues: Map<String, Boolean>,
    textValues: Map<String, String>,
    unsupportedKeys: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onOpenEditor: (String, String) -> Unit,
) {
    SectionHeader(group.title)
    SettingCard {
        group.items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            val parentOn = item.parent?.let { hookValues[it] == true } ?: true
            val enabled = gate && parentOn
            when (item.kind) {
                ItemKind.Switch -> {
                    val unsupported = item.key in unsupportedKeys
                    SwitchRow(
                        item = item,
                        accent = group.accent,
                        checked = !unsupported && (hookValues[item.key] ?: item.defaultValue),
                        enabled = enabled && !unsupported,
                        summaryOverride = if (unsupported) "当前知乎版本暂不支持" else null,
                        onToggle = onToggle,
                    )
                }
                ItemKind.Text -> TextRow(item, group.accent, enabled, textValues[item.key].orEmpty(), onOpenEditor)
            }
        }
    }
}

@Composable
private fun MaintainBlock(onClean: () -> Unit) {
    SectionHeader("维护")
    SettingCard {
        ActionRow(
            title = "清理临时文件",
            summary = "清理知乎内的广告缓存和日志文件",
            icon = R.drawable.ic_delete,
            accent = Accent.Amber,
            action = "清理",
            onClick = onClean,
        )
    }
}

@Composable
private fun AboutBlock(
    version: String,
    selfCheckStatus: String?,
    backendLabel: String,
    onAuthorClick: () -> Unit,
    onOpenLink: (String) -> Unit,
    onQueryStatus: () -> Unit,
    onExport: () -> Unit,
    onRestart: () -> Unit,
) {
    val packagerName = stringResource(R.string.packager_name)
    val packagerUrl = stringResource(R.string.packager_url)
    val sourceRepo = stringResource(R.string.source_repo)
    val releasesUrl = stringResource(R.string.releases_url)
    val feedbackUrl = stringResource(R.string.feedback_url)
    SectionHeader("关于")
    SettingCard {
        ValueRow("版本", version, R.drawable.ic_info, Accent.Blue, onClick = {})
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ValueRow("打包者", packagerName, R.drawable.ic_person, Accent.Blue) {
            onOpenLink(packagerUrl)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ValueRow("作者", "Shatyuka", R.drawable.ic_person, Accent.Blue, onClick = onAuthorClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LinkRow("帮助", "常见问题", R.drawable.ic_help) { onOpenLink("https://github.com/shatyuka/Zhiliao/wiki") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LinkRow("订阅更新", "GitHub Release", R.drawable.ic_rss_feed) {
            onOpenLink(releasesUrl)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LinkRow("反馈", "GitHub Issues", R.drawable.ic_help) {
            onOpenLink(feedbackUrl)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LinkRow("上游群", "原作者的 Telegram", R.drawable.ic_telegram) {
            onOpenLink("https://t.me/joinchat/OibCWxbdCMkJ2fG8J1DpQQ")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LinkRow("源代码", "DawnMagnet/Zhiliao", R.drawable.ic_github) {
            onOpenLink(sourceRepo)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LinkRow("捐赠", "支持原作者", R.drawable.ic_monetization) {
            onOpenLink("https://github.com/shatyuka/Zhiliao/wiki/Donate")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ActionRow(
            title = "自检",
            summary = selfCheckStatus ?: "点右侧刷新，向知乎查询当前状态",
            icon = R.drawable.ic_info,
            accent = Accent.Blue,
            action = "刷新",
            onClick = onQueryStatus,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ActionRow(
            title = "导出诊断",
            summary = "把这份报告发给开发者，只导出、不上传",
            icon = R.drawable.ic_info,
            accent = Accent.Blue,
            action = "导出",
            onClick = onExport,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ActionRow(
            title = "重启知乎",
            summary = "应用新设置",
            icon = R.drawable.ic_restart,
            accent = Accent.Blue,
            action = "重启",
            onClick = onRestart,
        )
    }
    Text(
        text = "配置通道：$backendLabel",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EulaCard(onAccept: () -> Unit) {
    SectionHeader("免责声明")
    SettingCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "本软件仅用于技术交流，请勿用于商业及非法用途，如产生法律纠纷与作者本人无关。\n\n" +
                    "本软件不会收集、上传您的个人信息，如有个人信息泄露与作者本人无关。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Text("我已知晓并同意")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    item: PrefItem,
    accent: Color,
    checked: Boolean,
    enabled: Boolean,
    summaryOverride: String?,
    onToggle: (String, Boolean) -> Unit,
) {
    val summary = summaryOverride ?: item.summary
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onToggle(item.key, !checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { IconBadge(item.icon, accent, enabled) },
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = { onToggle(item.key, it) },
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun TextRow(
    item: PrefItem,
    accent: Color,
    enabled: Boolean,
    value: String,
    onOpenEditor: (String, String) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onOpenEditor(item.key, value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { IconBadge(item.icon, accent, enabled) },
        headlineContent = { Text(item.title) },
        supportingContent = item.summary?.let { { Text(it) } },
        trailingContent = {
            Text(
                text = previewValue(item.key, value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    icon: Int,
    accent: Color,
    action: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { IconBadge(icon, accent, true) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            TextButton(onClick = onClick) { Text(action) }
        },
    )
}

@Composable
private fun ValueRow(
    title: String,
    value: String,
    icon: Int,
    accent: Color,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { IconBadge(icon, accent, true) },
        headlineContent = { Text(title) },
        trailingContent = {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
private fun LinkRow(title: String, value: String, icon: Int, onClick: () -> Unit) {
    ValueRow(title, value, icon, Accent.Blue, onClick)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    // Surface lays its content out in a Box. A column is what keeps every row visible.
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun IconBadge(icon: Int, accent: Color, enabled: Boolean) {
    val tint = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
    }
}

@Composable
private fun EditDialog(
    key: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val meta = Catalog.textItem(key)
    val isScript = key == "edit_js"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(meta?.title ?: "编辑") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                meta?.summary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (isScript) 160.dp else 88.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (isScript) FontFamily.Monospace else FontFamily.Default,
                    ),
                    placeholder = { Text(if (isScript) "// 例如 window.__zhiliao = 1" else "留空为忽略") },
                    keyboardOptions = KeyboardOptions.Default,
                    shape = MaterialTheme.shapes.medium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun previewValue(key: String, value: String): String = when {
    value.isBlank() -> "未设置"
    key == "edit_js" -> "${value.length} 字符"
    value.length > 10 -> value.take(10) + "…"
    else -> value
}
