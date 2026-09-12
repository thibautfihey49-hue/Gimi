@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.novalite.launcher

import android.content.*
import android.content.pm.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val pkg: String,
    val isSystem: Boolean,
    val icon: Bitmap? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var appReceiver: BroadcastReceiver
    
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            window.setWindowAnimations(0)
            super.onCreate(savedInstanceState)
            
            appReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_PACKAGE_REMOVED,
                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REPLACED -> {
                            if (intent.data?.schemeSpecificPart != packageName) {
                                refreshApps()
                            }
                        }
                    }
                }
            }
            
            registerReceiver(appReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            })
            
            setContent { NovaLiteApp(::refreshApps) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun refreshApps() {
        // Notifie le composant de recharger la liste
        refreshEvent.tryEmit(Unit)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(appReceiver) } catch (_: Exception) {}
    }
    
    companion object {
        val refreshEvent = kotlinx.coroutines.channels.Channel<Unit>(
            kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
    }
}

fun getAppIconSafe(pm: PackageManager, info: ApplicationInfo): Bitmap? {
    return try {
        val drawable = pm.getApplicationIcon(info) ?: return null
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        Bitmap.createScaledBitmap(bmp, 72, 72, false)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun NovaLiteApp(onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf(listOf<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var gameMode by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    
    fun loadApps() {
        scope.launch(Dispatchers.IO) {
            try {
                val installedPkgs = pm.getInstalledApplications(0)
                val list = mutableListOf<AppEntry>()
                
                for (info in installedPkgs) {
                    try {
                        val launchIntent = pm.getLaunchIntentForPackage(info.packageName)
                        if (launchIntent == null) continue
                        
                        val label = pm.getApplicationLabel(info)?.toString() ?: continue
                        val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        list.add(AppEntry(label, info.packageName, isSys, null))
                    } catch (_: Exception) {}
                }
                
                allApps = list.sortedBy { it.label.lowercase() }
                
                allApps.forEachIndexed { i, app ->
                    try {
                        val info = pm.getApplicationInfo(app.pkg, 0)
                        val icon = getAppIconSafe(pm, info)
                        if (icon != null) {
                            allApps = allApps.toMutableList().also { 
                                it[i] = app.copy(icon = icon)
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
        MainActivity.refreshEvent.receiveAsFlow().collect {
            isLoading = true
            loadApps()
        }
    }

    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { it.label.lowercase().contains(query.lowercase()) }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFFF8F9FA),
        surface = Color(0xFFFFFFFF),
        onBackground = Color(0xFF1A1A1A),
        onSurface = Color(0xFF2C2C2C),
        primary = Color(0xFF00C853)
    )) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8F9FA))
        ) {
            Column(Modifier.fillMaxSize()) {
                
                Spacer(Modifier.height(48.dp))
                
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Nova Lite",
                            color = Color(0xFF1A1A1A),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                isLoading -> "Chargement..."
                                gameMode -> "🎮 Mode jeu actif"
                                else -> "${allApps.size} applications"
                            },
                            color = Color(0xFF757575),
                            fontSize = 13.sp
                        )
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = gameMode,
                            onCheckedChange = { gameMode = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFFFFFF),
                                checkedTrackColor = Color(0xFF00C853)
                            )
                        )
                        IconButton(onClick = { 
                            ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }) {
                            Icon(
                                Icons.Default.Settings,
                                "Paramètres",
                                tint = Color(0xFF616161),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher une application...", color = Color(0xFF9E9E9E)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = Color(0xFF1A1A1A)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF1A1A1A),
                        unfocusedTextColor = Color(0xFF1A1A1A),
                        focusedContainerColor = Color(0xFFFFFFFF),
                        unfocusedContainerColor = Color(0xFFFFFFFF),
                        focusedIndicatorColor = Color(0xFF00C853),
                        unfocusedIndicatorColor = Color(0xFFE0E0E0),
                        cursorColor = Color(0xFF00C853)
                    ),
                    leadingIcon = { Text("🔍", fontSize = 16.sp) }
                )

                Spacer(Modifier.height(24.dp))

                if (isLoading && allApps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFF00C853),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Chargement des applications...", color = Color(0xFF757575))
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(filtered.size, key = { filtered[it].pkg }) { i ->
                            val app = filtered[i]
                            var showMenu by remember { mutableStateOf(false) }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (gameMode && app.pkg.lowercase().contains("callofduty")) {
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                                                    val myPkg = ctx.packageName
                                                    am.runningAppProcesses?.forEach { p ->
                                                        val pkg = p.processName
                                                        if (pkg != myPkg && !pkg.startsWith("android") && !pkg.startsWith("com.android")) {
                                                            try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                                withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(ctx, "🚀 MODE TURBO ACTIF", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        try {
                                            ctx.startActivity(pm.getLaunchIntentForPackage(app.pkg))
                                        } catch (_: Exception) {}
                                    },
                                    onLongClick = { showMenu = true }
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(78.dp)
                                        .background(Color(0xFFFFFFFF), RoundedCornerShape(20.dp))
                                        .border(1.dp, Color(0xFFE8E8E8), RoundedCornerShape(20.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (app.icon != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = app.icon.asImageBitmap(),
                                            contentDescription = app.label,
                                            modifier = Modifier.size(50.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                    } else {
                                        Text(
                                            app.label.firstOrNull()?.toString() ?: "?",
                                            color = Color(0xFF00C853),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    app.label,
                                    fontSize = 13.sp,
                                    color = Color(0xFF333333),
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(86.dp),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            if (showMenu) {
                                AlertDialog(
                                    onDismissRequest = { showMenu = false },
                                    containerColor = Color(0xFFFFFFFF),
                                    titleContentColor = Color(0xFF1A1A1A),
                                    textContentColor = Color(0xFF333333),
                                    text = {
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        try {
                                                            ctx.startActivity(
                                                                Intent(
                                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                                    Uri.parse("package:${app.pkg}")
                                                                )
                                                            )
                                                        } catch (_: Exception) {}
                                                        showMenu = false
                                                    }
                                                    .padding(vertical = 18.dp, horizontal = 8.dp)
                                            ) {
                                                Text("ℹ️ Informations", fontSize = 16.sp)
                                            }
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        try {
                                                            ctx.startActivity(
                                                                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
                                                            )
                                                        } catch (_: Exception) {}
                                                        showMenu = false
                                                    }
                                                    .padding(vertical = 18.dp, horizontal = 8.dp)
                                            ) {
                                                Text("🗑️ Désinstaller", color = Color(0xFFFF5252), fontSize = 16.sp)
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { showMenu = false }) {
                                            Text("Fermer", color = Color(0xFF00C853))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
