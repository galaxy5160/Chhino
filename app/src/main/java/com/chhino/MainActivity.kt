package com.chhino

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chhino.ui.ChhinoViewModel
import com.chhino.ui.navigation.NavRoutes
import com.chhino.ui.screens.*
import com.chhino.ui.theme.ChhinoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChhinoTheme {
                ChhinoRoot()
            }
        }
    }
}

@Composable
fun ChhinoRoot(viewModel: ChhinoViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val settings by viewModel.settings.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val latestOffer by viewModel.latestOffer.collectAsState()
    val rideHistory by viewModel.rideHistory.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    val analytics by viewModel.analyticsSummary.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()

    val startDestination = if (settings.onboardingCompleted) NavRoutes.HOME else NavRoutes.ONBOARDING
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    PermissionAutoPopup()

    val bottomTabs = listOf(
        Triple(NavRoutes.HOME, "Home", Icons.Default.DirectionsCar),
        Triple(NavRoutes.RULES, "Rules", Icons.Default.Tune),
        Triple(NavRoutes.HOME_PRIORITY, "My Home", Icons.Default.Home),
        Triple(NavRoutes.HISTORY, "History", Icons.Default.History),
        Triple(NavRoutes.ANALYTICS, "Stats", Icons.Default.Analytics)
    )

    Scaffold(
        bottomBar = {
            if (currentRoute != NavRoutes.ONBOARDING) {
                NavigationBar {
                    bottomTabs.forEach { (route, label, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                navController.navigate(route) {
                                    popUpTo(NavRoutes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(NavRoutes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        viewModel.completeOnboarding()
                        navController.navigate(NavRoutes.HOME) {
                            popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                        }
                    },
                    onSaveHomeLocation = { name, lat, lon ->
                        viewModel.saveHomeLocation(name, lat, lon)
                    },
                    onSaveInitialMinFare = { minFare ->
                        viewModel.saveInitialMinFare(minFare)
                    }
                )
            }
            composable(NavRoutes.HOME) {
                HomeScreen(
                    settings = settings,
                    captureState = captureState,
                    latestOffer = latestOffer,
                    homeLocation = homeLocation,
                    analytics = analytics,
                    onToggleMonitoring = viewModel::toggleMonitoring,
                    onToggleHomePriority = viewModel::toggleHomePriority,
                    onToggleBubble = viewModel::toggleBubble,
                    onSimulateTestOffer = viewModel::simulateTestOffer,
                    onNavigate = { navController.navigate(it) }
                )
            }
            composable(NavRoutes.RULES) {
                RulesScreen(
                    settings = settings,
                    onSaveRules = viewModel::updateRules
                )
            }
            composable(NavRoutes.HOME_PRIORITY) {
                HomePriorityScreen(
                    homePriorityEnabled = settings.homePriorityEnabled,
                    homeLocation = homeLocation,
                    onToggleHomePriority = viewModel::toggleHomePriority,
                    onSaveHome = viewModel::saveHomeLocation
                )
            }
            composable(NavRoutes.HISTORY) {
                HistoryScreen(
                    rides = rideHistory,
                    onDeleteRide = viewModel::deleteRide,
                    onClearAll = viewModel::clearAllHistory
                )
            }
            composable(NavRoutes.ANALYTICS) {
                AnalyticsScreen(summary = analytics)
            }
            composable(NavRoutes.DIAGNOSTICS) {
                DiagnosticsScreen(
                    checks = diagnostics,
                    onRefresh = viewModel::refreshDiagnostics
                )
            }
            composable(NavRoutes.PRIVACY) {
                PrivacyScreen(
                    onClearHistory = viewModel::clearAllHistory,
                    onClearHome = viewModel::clearHomeLocation
                )
            }
            composable(NavRoutes.GUIDE) {
                GuideScreen()
            }
        }
    }
}

@Composable
fun PermissionAutoPopup() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    var dismissed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifGranted = remember(tick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    val overlayGranted = remember(tick) { Settings.canDrawOverlays(context) }

    val batteryIgnored = remember(tick) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    val accessibilityEnabled = remember(tick) {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains(context.packageName)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    // Trigger native runtime popup for notifications automatically on launch
    LaunchedEffect(Unit) {
        if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val allReady = notifGranted && overlayGranted && batteryIgnored && accessibilityEnabled
    if (!allReady && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Permissions Required", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chhino ko background me chalane ke liye neeche ki permissions allow karein:")
                    PermissionRow("Notifications", notifGranted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    PermissionRow("Battery Unrestricted", batteryIgnored) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                    PermissionRow("Floating Bubble (Overlay)", overlayGranted) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                    PermissionRow("Accessibility Service", accessibilityEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dismissed = true }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (granted) "✓ $label" else "• $label",
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (!granted) {
            TextButton(onClick = onGrant) {
                Text("Allow")
            }
        }
    }
}
