package com.mnbt.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mnbt.app.R
import com.mnbt.app.ui.cloud.CloudPairScanScreen
import com.mnbt.app.ui.cloud.CloudPairTrustScreen
import com.mnbt.app.ui.cloud.CloudScreen
import com.mnbt.app.ui.cloud.CloudServerDetailScreen
import com.mnbt.app.ui.cloud.CloudSiteDetailScreen
import com.mnbt.app.ui.cloud.CloudSiteManageScreen
import com.mnbt.app.ui.database.DatabaseScreen
import com.mnbt.app.ui.database.SqlImportScreen
import com.mnbt.app.ui.domainssl.DomainsScreen
import com.mnbt.app.ui.domainssl.SslScreen
import com.mnbt.app.ui.editor.EditorScreen
import com.mnbt.app.ui.filemanager.FileBrowserScreen
import com.mnbt.app.ui.hostmgmt.CertManageScreen
import com.mnbt.app.ui.hostmgmt.HostManageScreen
import com.mnbt.app.ui.hosts.HostDetailScreen
import com.mnbt.app.ui.hosts.HostsScreen
import com.mnbt.app.ui.mine.MineScreen
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.nav.requireSiteId
import com.mnbt.app.ui.ops.DeployScreen
import com.mnbt.app.ui.ops.MonitorScreen
import com.mnbt.app.ui.ops.NoticeScreen
import com.mnbt.app.ui.pair.ManualPairScreen
import com.mnbt.app.ui.pair.PairScanScreen
import com.mnbt.app.ui.pair.PairTrustScreen
import com.mnbt.app.ui.site.CacheRulesScreen
import com.mnbt.app.ui.site.PassdirScreen
import com.mnbt.app.ui.site.SiteAGzipScreen
import com.mnbt.app.ui.site.SiteAHotlinkScreen
import com.mnbt.app.ui.site.SiteAIndexdocScreen
import com.mnbt.app.ui.site.SiteAPhpScreen
import com.mnbt.app.ui.site.SiteARewriteScreen
import com.mnbt.app.ui.site.SiteARundirScreen
import com.mnbt.app.ui.site.SitePasswordScreen
import com.mnbt.app.ui.ssh.SshEditScreen
import com.mnbt.app.ui.ssh.SshTerminalScreen
import com.mnbt.app.ui.terminal.TerminalTabScreen
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

object Routes {
    const val CLOUD = "cloud"
    const val HOSTS = "hosts"
    const val TERMINAL = "terminal"
    const val MINE = "mine"
}

private enum class BottomTab(
    val route: String,
    @StringRes val labelRes: Int,
    val iconRes: Int,
) {
    Cloud(Routes.CLOUD, R.string.tab_cloud, R.drawable.ic_cloud),
    Terminal(Routes.TERMINAL, R.string.tab_terminal, R.drawable.ic_terminal_2),
    Hosts(Routes.HOSTS, R.string.tab_hosts, R.drawable.ic_server),
    Mine(Routes.MINE, R.string.tab_mine, R.drawable.ic_user),
}

private val TAB_ROUTES = BottomTab.entries.map { it.route }.toSet()

private const val TabFadeMs = 120
private const val PageSlideMs = 180

private fun String?.isTabRoute(): Boolean = this != null && this in TAB_ROUTES

/**
 * 转场判定。库里 NavHost 的默认值是 fadeIn/fadeOut(tween(700))（navigation-compose 2.8.5，
 * NavHost.kt 里写死），700ms 交叉淡入让每次切 tab 都像卡了一下，所以整块覆盖：
 * tab 之间只有短淡入，进/出功能页才带水平方向感。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabToTab(): Boolean =
    initialState.destination.route.isTabRoute() && targetState.destination.route.isTabRoute()

/** 主机功能页统一注册：route = host/{hostId}/<feature>，{hostId} 参数集中带上。 */
private fun NavGraphBuilder.hostFeature(
    feature: String,
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(
    route = "host/{hostId}/$feature",
    arguments = listOf(Destinations.hostIdArg()),
) { entry -> content(entry) }

/** SiteB 组（Passdir/Password/Cache）入口是 (hostId, onBack, onRepair)，这里适配。 */
@Composable
private fun siteBArgs(entry: NavBackStackEntry, navController: NavController): Pair<Long, () -> Unit> =
    entry.requireHostId() to { navController.popBackStack() }

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val palette = LocalAppPalette.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        containerColor = palette.background,
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                    NavigationBar(
                        containerColor = palette.surface,
                        tonalElevation = 0.dp,
                    ) {
                        BottomTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(tab.iconRes),
                                        contentDescription = stringResource(tab.labelRes),
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = palette.primary,
                                    selectedTextColor = palette.primary,
                                    unselectedIconColor = palette.textSecondary,
                                    unselectedTextColor = palette.textSecondary,
                                    indicatorColor = palette.background,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CLOUD,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (isTabToTab()) {
                    fadeIn(animationSpec = tween(TabFadeMs))
                } else {
                    slideInHorizontally(animationSpec = tween(PageSlideMs)) { it / 4 } +
                        fadeIn(animationSpec = tween(PageSlideMs))
                }
            },
            // 被覆盖的页面只做淡出：同一时刻只允许一层在动，两层一起滑会糊成一片
            exitTransition = {
                if (isTabToTab()) fadeOut(animationSpec = tween(TabFadeMs))
                else fadeOut(animationSpec = tween(PageSlideMs / 2))
            },
            popEnterTransition = {
                if (isTabToTab()) fadeIn(animationSpec = tween(TabFadeMs))
                else fadeIn(animationSpec = tween(PageSlideMs / 2))
            },
            // 返回时子页往右退出，和进来时的方向对称
            popExitTransition = {
                if (isTabToTab()) {
                    fadeOut(animationSpec = tween(TabFadeMs))
                } else {
                    slideOutHorizontally(animationSpec = tween(PageSlideMs)) { it / 4 } +
                        fadeOut(animationSpec = tween(PageSlideMs))
                }
            },
        ) {
            composable(Routes.CLOUD) { CloudScreen(navController) }
            composable(Routes.HOSTS) { HostsScreen(navController) }
            composable(Routes.TERMINAL) { TerminalTabScreen(navController) }
            composable(Routes.MINE) { MineScreen(navController) }

            // 配对流程
            composable(Destinations.PAIR_SCAN) { PairScanScreen(navController) }
            composable(Destinations.PAIR_MANUAL) { ManualPairScreen(navController) }
            composable(
                route = Destinations.PAIR_TRUST,
                arguments = listOf(
                    navArgument(Destinations.ARG_BASE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(Destinations.ARG_CODE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry -> PairTrustScreen(navController, entry) }

            // 云服务器：绑定流程与详情
            composable(Destinations.CLOUD_PAIR_SCAN) { CloudPairScanScreen(navController) }
            composable(
                route = Destinations.CLOUD_PAIR_TRUST,
                arguments = listOf(
                    navArgument(Destinations.ARG_BASE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(Destinations.ARG_CODE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(Destinations.ARG_SYSTEM_TYPE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry -> CloudPairTrustScreen(navController, entry) }
            composable(
                route = Destinations.CLOUD_SITE_DETAIL,
                arguments = listOf(
                    navArgument(Destinations.ARG_SITE_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                CloudSiteDetailScreen(
                    navController = navController,
                    siteId = entry.requireSiteId(),
                )
            }
            composable(
                route = Destinations.CLOUD_SERVER_DETAIL,
                arguments = listOf(
                    navArgument(Destinations.ARG_SITE_ID) { type = NavType.LongType },
                    navArgument(Destinations.ARG_HOST_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                CloudServerDetailScreen(
                    navController = navController,
                    siteId = entry.requireSiteId(),
                    hostId = entry.requireHostId(),
                )
            }

            // 我的 tab 二级页
            composable(Destinations.MINE_HOSTS) { HostManageScreen(navController) }
            composable(Destinations.MINE_CLOUD_SITES) { CloudSiteManageScreen(navController) }
            composable(Destinations.MINE_CERTS) { CertManageScreen(navController) }

            // 主机详情
            composable(
                route = Destinations.HOST_DETAIL,
                arguments = listOf(Destinations.hostIdArg()),
            ) { entry -> HostDetailScreen(navController, entry.requireHostId()) }

            // 站点设置 · 运行/性能组（SiteA）
            hostFeature(Destinations.F_PHP) { SiteAPhpScreen(navController, it) }
            hostFeature(Destinations.F_RUNDIR) { SiteARundirScreen(navController, it) }
            hostFeature(Destinations.F_INDEXDOC) { SiteAIndexdocScreen(navController, it) }
            hostFeature(Destinations.F_REWRITE) { SiteARewriteScreen(navController, it) }
            hostFeature(Destinations.F_GZIP) { SiteAGzipScreen(navController, it) }
            hostFeature(Destinations.F_HOTLINK) { SiteAHotlinkScreen(navController, it) }

            // 站点设置 · 账号安全/缓存组（SiteB）
            hostFeature(Destinations.F_PASSDIR) { entry ->
                val (hostId, onBack) = siteBArgs(entry, navController)
                PassdirScreen(
                    hostId = hostId,
                    onBack = onBack,
                    onRepair = { navController.navigate(Destinations.PAIR_SCAN) },
                )
            }
            hostFeature(Destinations.F_PASSWORD) { entry ->
                val (hostId, onBack) = siteBArgs(entry, navController)
                SitePasswordScreen(
                    hostId = hostId,
                    onBack = onBack,
                    onRepair = { navController.navigate(Destinations.PAIR_SCAN) },
                )
            }
            hostFeature(Destinations.F_CACHE) { entry ->
                val (hostId, onBack) = siteBArgs(entry, navController)
                CacheRulesScreen(
                    hostId = hostId,
                    onBack = onBack,
                    onRepair = { navController.navigate(Destinations.PAIR_SCAN) },
                )
            }

            // 域名 / SSL
            hostFeature(Destinations.F_DOMAINS) { DomainsScreen(navController, it) }
            hostFeature(Destinations.F_SSL) { SslScreen(navController, it) }

            // 数据库 / SQL 导入
            hostFeature(Destinations.F_DATABASE) { DatabaseScreen(navController, it) }
            hostFeature(Destinations.F_SQL_IMPORT) { SqlImportScreen(navController, it) }

            // 运维
            hostFeature(Destinations.F_MONITOR) { MonitorScreen(navController, it) }
            hostFeature(Destinations.F_NOTICE) { NoticeScreen(navController, it) }
            hostFeature(Destinations.F_DEPLOY) { DeployScreen(navController, it) }

            // 文件与编辑
            hostFeature(Destinations.F_FILES) { FileBrowserScreen(navController, it) }
            composable(
                route = Destinations.FILE_EDITOR,
                arguments = listOf(
                    Destinations.hostIdArg(),
                    navArgument(Destinations.ARG_PATH) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry -> EditorScreen(navController, entry) }

            // SSH
            composable(
                route = Destinations.SSH_EDIT,
                arguments = listOf(
                    navArgument(Destinations.ARG_SESSION_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry -> SshEditScreen(navController, entry) }
            composable(
                route = Destinations.SSH_TERMINAL,
                arguments = listOf(
                    navArgument(Destinations.ARG_SESSION_ID) { type = NavType.LongType },
                ),
            ) { entry -> SshTerminalScreen(navController, entry) }
        }
    }
}
