package app.blue.sms.ui.nav

import app.blue.sms.ui.common.BlueIcons

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.blue.sms.ui.common.PlaceholderScreen
import app.blue.sms.ui.edit.EditTxnScreen
import app.blue.sms.ui.insights.InsightsScreen
import app.blue.sms.ui.transactions.NO_ARG
import app.blue.sms.ui.transactions.TransactionsScreen
import app.blue.sms.ui.review.REVIEW_MODE_KEY
import app.blue.sms.ui.review.ReviewMode
import app.blue.sms.ui.review.ReviewScreen
import app.blue.sms.ui.review.SkippedScreen
import app.blue.sms.ui.settings.MANAGE_CATEGORIES
import app.blue.sms.ui.settings.MANAGE_FOR_WHOM
import app.blue.sms.ui.settings.MANAGE_SPAM
import app.blue.sms.ui.spam.SpamScreen
import app.blue.sms.ui.upcoming.ProposalsScreen
import app.blue.sms.ui.settings.ManageListScreen
import app.blue.sms.data.db.SmsFilter
import app.blue.sms.ui.debug.SmsListScreen
import app.blue.sms.ui.debug.AiCheckScreen
import app.blue.sms.ui.home.HomeScreen
import app.blue.sms.ui.permission.PermissionHelpScreen
import app.blue.sms.ui.settings.SettingsScreen
import app.blue.sms.ui.sim.SimPickerScreen
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object ReviewRoute
@Serializable object TransactionsRoute
@Serializable object InsightsRoute
@Serializable object SpamRoute
@Serializable object SettingsRoute
@Serializable data class SimPickerRoute(val scanAfter: Boolean = false)
@Serializable object PermissionHelpRoute
/** [id] is read by EditTxnViewModel through SavedStateHandle. */
@Serializable data class EditTxnRoute(val id: Long)
@Serializable object SkippedRoute
@Serializable object ProposalsRoute
@Serializable object AiCheckRoute
@Serializable data class ManageRoute(val kind: String)
/** Insights drill-down. Field names are read by TransactionsViewModel/PeriodHolder via SavedStateHandle. */
@Serializable data class TxnDrillRoute(
    val title: String,
    val categoryId: Long = NO_ARG,
    val direction: String = "",
    val kind: String,
    val start: Long,
    val end: Long,
)
/** [startFilter] is read by SmsListViewModel through SavedStateHandle. */
@Serializable data class SmsListRoute(val startFilter: String = SmsFilter.ALL, val starredOnly: Boolean = false)

private const val SIM_CHOSEN_KEY = "sim_chosen"

/** Coming back after this long lands on Review, as a fresh start does. */
private const val BACK_TO_REVIEW_AFTER_MS = 30 * 60 * 1000L

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, "Home", BlueIcons.Home),
    Tab(ReviewRoute, "Review", BlueIcons.Review),
    Tab(TransactionsRoute, "Entries", BlueIcons.Entries),
    Tab(InsightsRoute, "Insights", BlueIcons.Insights),
    Tab(SpamRoute, "Spam", BlueIcons.Spam),
)

@Composable
fun BlueNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = tabs.any { t -> dest?.hasRoute(t.route::class) == true }

    // Review is the start screen and stays at the bottom of the back stack, so popping to it
    // works from any tab. An open edit is left alone so typed changes aren't lost.
    var leftAt by rememberSaveable { mutableLongStateOf(0L) }
    LifecycleStartEffect(Unit) {
        if (leftAt != 0L && System.currentTimeMillis() - leftAt >= BACK_TO_REVIEW_AFTER_MS &&
            nav.currentDestination?.hasRoute(EditTxnRoute::class) != true
        ) {
            nav.popBackStack<ReviewRoute>(inclusive = false)
        }
        onStopOrDispose { leftAt = System.currentTimeMillis() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTab) {
                BlueBottomBar(
                    tabs = tabs,
                    isSelected = { t -> dest?.hierarchy?.any { it.hasRoute(t.route::class) } == true },
                    onSelect = { t ->
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ReviewRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<HomeRoute> { e ->
                val simChosen by e.savedStateHandle.getStateFlow(SIM_CHOSEN_KEY, false).collectAsStateWithLifecycle()
                HomeScreen(
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                    onOpenDebug = { nav.navigate(SmsListRoute()) },
                    onOpenStarred = { nav.navigate(SmsListRoute(SmsFilter.STARRED, starredOnly = true)) },
                    onOpenProposals = { nav.navigate(ProposalsRoute) },
                    onOpenReview = {
                        nav.navigate(ReviewRoute) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNeedSim = { nav.navigate(SimPickerRoute(scanAfter = true)) },
                    onPermissionBlocked = { nav.navigate(PermissionHelpRoute) },
                    simJustChosen = simChosen,
                    onSimChosenHandled = { e.savedStateHandle[SIM_CHOSEN_KEY] = false },
                )
            }
            composable<ReviewRoute> {
                ReviewScreen(
                    onOpenEdit = { nav.navigate(EditTxnRoute(it)) },
                    onOpenSkipped = { nav.navigate(SkippedRoute) },
                )
            }
            composable<EditTxnRoute> {
                EditTxnScreen(onDone = {
                    // Spec: confirming returns to the swipe view.
                    nav.previousBackStackEntry?.takeIf { it.destination.hasRoute(ReviewRoute::class) }
                        ?.savedStateHandle?.set(REVIEW_MODE_KEY, ReviewMode.CARDS.name)
                    nav.popBackStack()
                })
            }
            composable<SkippedRoute> {
                SkippedScreen(onBack = { nav.popBackStack() }, onOpenEdit = { nav.navigate(EditTxnRoute(it)) })
            }
            composable<ManageRoute> { ManageListScreen(onBack = { nav.popBackStack() }) }
            composable<TransactionsRoute> {
                TransactionsScreen("Entries", onBack = null, onOpenEdit = { nav.navigate(EditTxnRoute(it)) })
            }
            composable<InsightsRoute> {
                InsightsScreen(onOpenCategory = { id, name, dir, p ->
                    nav.navigate(TxnDrillRoute(name, id, dir.name, p.kind.name, p.start.toEpochDay(), p.end.toEpochDay()))
                })
            }
            composable<TxnDrillRoute> { e ->
                TransactionsScreen(e.toRoute<TxnDrillRoute>().title, onBack = { nav.popBackStack() }, onOpenEdit = { nav.navigate(EditTxnRoute(it)) })
            }
            composable<SpamRoute> { SpamScreen() }
            composable<AiCheckRoute> { AiCheckScreen(onBack = { nav.popBackStack() }) }
            composable<ProposalsRoute> { ProposalsScreen(onBack = { nav.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onChooseSim = { nav.navigate(SimPickerRoute(scanAfter = false)) },
                    onManageCategories = { nav.navigate(ManageRoute(MANAGE_CATEGORIES)) },
                    onManageForWhom = { nav.navigate(ManageRoute(MANAGE_FOR_WHOM)) },
                    onManageSpam = { nav.navigate(ManageRoute(MANAGE_SPAM)) },
                )
            }
            composable<SimPickerRoute> { e ->
                val route = e.toRoute<SimPickerRoute>()
                SimPickerScreen(
                    onBack = { nav.popBackStack() },
                    onPermissionHelp = { nav.navigate(PermissionHelpRoute) },
                    onChosen = {
                        if (route.scanAfter) nav.previousBackStackEntry?.savedStateHandle?.set(SIM_CHOSEN_KEY, true)
                        nav.popBackStack()
                    },
                )
            }
            composable<PermissionHelpRoute> { PermissionHelpScreen(onBack = { nav.popBackStack() }) }
            composable<SmsListRoute> { e ->
                val route = e.toRoute<SmsListRoute>()
                SmsListScreen(
                    title = if (route.starredOnly) "Starred" else "Scanned SMS (debug)",
                    onBack = { nav.popBackStack() },
                    showFilters = !route.starredOnly,
                    onCheckAi = { nav.navigate(AiCheckRoute) },
                )
            }
        }
    }
}

/** Plain tabs: grey icon and label, the selected one in ink with a small blue dot under it. */
@Composable
private fun BlueBottomBar(tabs: List<Tab>, isSelected: (Tab) -> Boolean, onSelect: (Tab) -> Unit) {
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surface).navigationBarsPadding()
    ) {
        androidx.compose.material3.HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
            tabs.forEach { t ->
                val on = isSelected(t)
                val color = if (on) androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.weight(1f)
                        .selectable(selected = on, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(t) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
                ) {
                    Icon(t.icon, contentDescription = null, tint = color, modifier = androidx.compose.ui.Modifier.size(24.dp))
                    Text(
                        t.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                    )
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.size(5.dp).background(
                            if (on) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                    )
                }
            }
        }
    }
}
