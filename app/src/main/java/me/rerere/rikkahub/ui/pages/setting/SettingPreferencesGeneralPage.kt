package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.permission.PermissionMode
import me.rerere.rikkahub.data.permission.PermissionRule
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var ttsPlaybackSpeed by remember(settings.defaultTTSPlaybackSpeed) {
        mutableFloatStateOf(settings.defaultTTSPlaybackSpeed)
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        trailingContent = {
                            Switch(
                                checked = createNewConversationOnStart,
                                onCheckedChange = { createNewConversationOnStart = it }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.sendOnEnter,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showMessageJumper,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.showMessageJumper) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.messageJumperOnLeft,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                    }
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableAutoScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.useAppIconStyleLoadingIndicator,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_background_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableBlurEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableBlurEffect) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_type)) },
                            supportingContent = {
                                Select(
                                    options = BackgroundEffectType.entries,
                                    selectedOption = displaySetting.backgroundEffectType,
                                    onOptionSelected = {
                                        updateDisplaySetting(displaySetting.copy(backgroundEffectType = it))
                                    },
                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                    optionToString = {
                                        when (it) {
                                            BackgroundEffectType.BLUR -> stringResource(R.string.setting_display_page_background_effect_blur)
                                            BackgroundEffectType.GLASS -> stringResource(R.string.setting_display_page_background_effect_glass)
                                        }
                                    },
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableMessageGenerationHapticEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.skipCropImage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.pasteLongTextAsFile,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.pasteLongTextThreshold.toFloat(),
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                        },
                                        valueRange = 100f..10000f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${displaySetting.pasteLongTextThreshold}")
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableVolumeKeyScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.volumeKeyScrollRatio,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                        },
                                        valueRange = 0.25f..1.0f,
                                        steps = 2,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                                }
                            }
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_tts_page_default_playback_speed))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_tts_page_default_playback_speed_description))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = ttsPlaybackSpeed,
                                        onValueChange = {
                                            ttsPlaybackSpeed = (it * 10).roundToInt() / 10f
                                        },
                                        onValueChangeFinished = {
                                            vm.updateSettings(
                                                settings.copy(defaultTTSPlaybackSpeed = ttsPlaybackSpeed)
                                            )
                                        },
                                        valueRange = 0.5f..2.0f,
                                        steps = 14,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(text = "x${"%.1f".format(ttsPlaybackSpeed)}")
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadQuoted,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadOutsideBrackets,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoPlayTTSAfterGeneration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                                }
                            )
                        },
                    )
                }
            }

            // 工具权限：一个门卫，查通行证、规矩、档位
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_permission_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_permission_mode_title)) },
                        supportingContent = {
                            Text(context.getString(settings.permissionMode.descRes()))
                        },
                        trailingContent = {
                            Select(
                                options = PermissionMode.entries,
                                selectedOption = settings.permissionMode,
                                onOptionSelected = { mode ->
                                    vm.updateSettings(settings.copy(permissionMode = mode))
                                },
                                optionToString = { context.getString(it.titleRes()) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_permission_rules_title)) },
                        supportingContent = {
                            Text(
                                if (settings.permissionRules.isEmpty()) {
                                    stringResource(R.string.setting_permission_rules_empty)
                                } else {
                                    stringResource(
                                        R.string.setting_permission_rules_count,
                                        settings.permissionRules.size,
                                    )
                                }
                            )
                        },
                    )
                    settings.permissionRules.forEach { rule ->
                        item(
                            headlineContent = { Text(permissionRuleLabel(rule)) },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        vm.updateSettings(
                                            settings.copy(
                                                permissionRules = settings.permissionRules - rule,
                                            )
                                        )
                                    },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Delete02,
                                        contentDescription = stringResource(R.string.setting_permission_rule_delete),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun PermissionMode.titleRes(): Int = when (this) {
    PermissionMode.PLAN -> R.string.setting_permission_mode_plan
    PermissionMode.ASK -> R.string.setting_permission_mode_ask
    PermissionMode.EDIT -> R.string.setting_permission_mode_edit
    PermissionMode.TRUSTED -> R.string.setting_permission_mode_trusted
    PermissionMode.FULL -> R.string.setting_permission_mode_full
}

private fun PermissionMode.descRes(): Int = when (this) {
    PermissionMode.PLAN -> R.string.setting_permission_mode_plan_desc
    PermissionMode.ASK -> R.string.setting_permission_mode_ask_desc
    PermissionMode.EDIT -> R.string.setting_permission_mode_edit_desc
    PermissionMode.TRUSTED -> R.string.setting_permission_mode_trusted_desc
    PermissionMode.FULL -> R.string.setting_permission_mode_full_desc
}

@Composable
private fun permissionRuleLabel(rule: PermissionRule): String = when {
    rule.commandPrefix != null -> stringResource(
        R.string.setting_permission_rule_command,
        rule.commandPrefix,
    )
    rule.pathPrefix != null -> stringResource(
        R.string.setting_permission_rule_path,
        rule.pathPrefix,
    )
    else -> stringResource(R.string.setting_permission_rule_tool, rule.tool)
}
