@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.novalite.launcher

import android.content.*
import android.content.pm.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Context.dataStore by preferencesDataStore("nova_dock")

data class AppEntry(
    val label: String,
    val pkg: String,
    val isSystem: Boolean,
    val icon: Bitmap? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setWindowAnimations(0)
        super.onCreate(savedInstanceState)
        setContent { NovaLiteApp() }
    }
}

fun getAppIcon(pm: PackageManager, info: ResolveInfo): Bitmap? {
    return try {
        val drawable = info.loadIcon(pm)
        val w = drawable.intrinsicWidth.coerceAtLeast(96)
        val h = drawable.intrinsicHeight.coerceAtLeast(96)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        Bitmap.createScaledBitmap(bmp, 64, 64, false)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun NovaLiteApp() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var gameMode by remember { mutableStateOf(false) }
    var dock by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 📋 Charger la liste des apps
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                val list = mutableListOf<AppEntry>()
                
                for (info in resolveInfos) {
                    try {
                        val label = info.loadLabel(pm).toString()
                        val pkg = info.activityInfo.packageName
                        val isSys = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val icon = getAppIcon(pm, info)
                        list.add(AppEntry(label, pkg, isSys, icon))
                    } catch (_: Exception) {}
                }
                
                apps = list.sortedBy { it.label.lowercase() }
            } catch (_: Exception) {}

            // 📂 Lire DataStore UNE SEULE FOIS (PAS de collect = pas de bug Compose)
            val defaultDock = listOf(
                "com.android.dialer", "com.android.mms", "com.android.camera",
                "com.whatsapp", "com.spotify.music", "com.google.android.youtube",
                "com.activision.callofduty.shooter"
            )
            try {
                val prefs = ctx.dataStore.data.first()
                val saved = prefs[stringPreferencesKey("dock")]
                dock = saved?.split(",") ?: defaultDock
            } catch (_: Exception) {
                dock = defaultDock
            }
        }
    }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true, ignoreCase = true) }.take(30)
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.padding(8.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (gameMode) "🎮 GAME MODE ON" else "${apps.size} apps",
                        color = if (gameMode) Color(0xFF00FF88) else Color(0xFF666666),
                        fontSize = 11.sp
                    )
                    Switch(
                        checked = gameMode,
                        onCheckedChange = { gameMode = it },
                        modifier = Modifier.size(28.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00FF88),
                            uncheckedThumbColor = Color(0xFF444444)
                        )
                    )
                }

                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedPlaceholderColor = Color(0xFF555555),
                        unfocusedPlaceholderColor = Color(0xFF555555),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color(0xFF00FF88),
                        unfocusedIndicatorColor = Color(0xFF333333)
                    )
                )

                Spacer(Modifier.height(8.dp))

                if (filtered.isEmpty() && query.length >= 2) {
                    Text(
                        "🔍 Rechercher sur le web",
                        color = Color(0xFF0099FF),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")
                                    )
                                )
                            }
                            .padding(4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(filtered.size, key = { filtered[it].pkg }) { i ->
                        val app = filtered[i]
                        var showSheet by remember { mutableStateOf(false) }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (gameMode && app.pkg.contains("callofduty", ignoreCase = true)) {
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

                                            try {
                                                ctx.sendBroadcast(
                                                    Intent("com.miui.gamebooster.action.BOOST").apply {
                                                        setPackage("com.xiaomi.gameboost")
                                                        putExtra("package", app.pkg)
                                                        putExtra("boost_mode", 1)
                                                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                                    }
                                                )
                                            } catch (_: Exception) {}

                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    ctx,
                                                    "🚀 MODE TURBO — ${app.label}",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    try {
                                        ctx.startActivity(pm.getLaunchIntentForPackage(app.pkg))
                                    } catch (_: Exception) {}
                                },
                                onLongClick = { showSheet = true }
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0xFF121212), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (app.icon != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = app.icon.asImageBitmap(),
                                        contentDescription = app.label,
                                        modifier = Modifier.size(40.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        app.label.firstOrNull()?.toString() ?: "?",
                                        color = Color.White,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                            Text(
                                app.label,
                                fontSize = 9.sp,
                                color = Color(0xFF999999),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        if (showSheet) {
                            ModalBottomSheet(
                                onDismissRequest = { showSheet = false },
                                containerColor = Color(0xFF1A1A1A)
                            ) {
                                ListItem(
                                    headlineContent = { Text("ℹ️ Infos de l'application", color = Color.White) },
                                    modifier = Modifier.clickable {
                                        try {
                                            ctx.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${app.pkg}")
                                                )
                                            )
                                        } catch (_: Exception) {}
                                        showSheet = false
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("🗑️ Désinstaller", color = Color(0xFFFF5555)) },
                                    modifier = Modifier.clickable {
                                        try {
                                            ctx.startActivity(
                                                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
                                            )
                                        } catch (_: Exception) {}
                                        showSheet = false
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }

                Surface(
                    color = Color(0xFF0F0F0F),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        repeat(7) { idx ->
                            val pkg = dock.getOrNull(idx)
                            val entry = apps.find { it.pkg == pkg }

                            if (entry != null) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable {
                                            try {
                                                ctx.startActivity(pm.getLaunchIntentForPackage(entry.pkg))
                                            } catch (_: Exception) {}
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (entry.icon != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = entry.icon.asImageBitmap(),
                                            contentDescription = entry.label,
                                            modifier = Modifier.size(38.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                    } else {
                                        Text(
                                            entry.label.firstOrNull()?.toString() ?: "?",
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .border(1.dp, Color(0xFF333333), androidx.compose.foundation.shape.CircleShape)
                                        .clickable {
                                            scope.launch {
                                                try {
                                                    ctx.dataStore.edit { prefs ->
                                                        val key = stringPreferencesKey("dock")
                                                        val current = dock.toMutableList()
                                                        while (current.size <= idx) current.add("")
                                                        current[idx] = apps.firstOrNull()?.pkg ?: ""
                                                        prefs[key] = current.joinToString(",")
                                                        dock = current
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+", color = Color(0xFF666666), fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
