package com.screenshot.ai.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.R
import com.screenshot.ai.ui.common.SectionLabel
import com.screenshot.ai.ui.common.SettingRow
import com.screenshot.ai.ui.theme.PageHorizontalPadding

/**
 * 关于界面。
 *
 * 检查更新通过 GitHub Releases API 完成；有更新时展示版本号、更新说明与下载入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState()),
        ) {
            // ---------------- 头部：Logo + 名称 + 版本 ----------------
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "📸", style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_version, viewModel.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 检查更新 ----------------
            SectionLabel(text = stringResource(R.string.about_check_update))

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PageHorizontalPadding),
                shape = MaterialTheme.shapes.large,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                SettingRow(
                    title = stringResource(R.string.about_check_update),
                    subtitle =
                        when (val state = updateState) {
                            is AboutViewModel.UpdateState.Checking -> stringResource(R.string.about_checking)
                            is AboutViewModel.UpdateState.UpToDate -> stringResource(R.string.about_up_to_date)
                            is AboutViewModel.UpdateState.Available ->
                                stringResource(R.string.about_update_found, state.version)

                            is AboutViewModel.UpdateState.Failed ->
                                stringResource(R.string.about_check_fail, state.message)

                            else -> null
                        },
                    modifier =
                        Modifier.clickable {
                            if (updateState !is AboutViewModel.UpdateState.Checking) viewModel.checkUpdate()
                        },
                    trailing = {
                        if (updateState is AboutViewModel.UpdateState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )

                // 发现新版本：展示更新说明 + 下载按钮
                (updateState as? AboutViewModel.UpdateState.Available)?.let { state ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        if (state.changelog.isNotBlank()) {
                            Text(
                                text = state.changelog.take(600),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        TextButton(onClick = { openUrl(context, state.downloadUrl) }) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.about_update_download))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ---------------- 开源协议 ----------------
            SectionLabel(text = stringResource(R.string.about_license_title))

            InfoCard(
                icon = Icons.Rounded.Verified,
                title = "GNU General Public License v3.0",
                description = stringResource(R.string.about_license_desc),
            )

            // ---------------- 仓库 ----------------
            SectionLabel(text = stringResource(R.string.about_repo))

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PageHorizontalPadding),
                shape = MaterialTheme.shapes.large,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                SettingRow(
                    title = viewModel.repoUrl.removePrefix("https://"),
                    modifier = Modifier.clickable { openUrl(context, viewModel.repoUrl) },
                    leading = {
                        Icon(
                            imageVector = Icons.Rounded.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            // ---------------- 作者 ----------------
            SectionLabel(text = stringResource(R.string.about_author_title))

            InfoCard(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.about_author_name),
                description = stringResource(R.string.about_author_desc),
            )

            // ---------------- 致谢 ----------------
            SectionLabel(text = stringResource(R.string.about_thanks_title))

            InfoCard(
                icon = Icons.Rounded.Verified,
                title = stringResource(R.string.about_thanks_title),
                description = stringResource(R.string.about_thanks_desc),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Copyright © 2025 Screenshot AI contributors",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** 统一的说明卡片 */
@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 用系统浏览器打开链接；没有可用浏览器时静默失败（避免崩溃） */
private fun openUrl(
    context: android.content.Context,
    url: String,
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
