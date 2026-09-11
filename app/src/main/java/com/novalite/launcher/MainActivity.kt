package com.novalite.launcher

import android.content.*
import android.content.pm.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore("nova_dock")

data class AppEntry(val resolveInfo: ResolveInfo, val label: String, val pkg: String, val isSystem: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setWindowAnimations(0)
        super.onCreate(savedInstanceState)
        setContent { NovaLiteApp() }
    }
}

@Composable
fun NovaLiteApp() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var gameMode by remember { mutableStateOf(false) }
    var dock by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                val isSys = (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                AppEntry(it, it.loadLabel(pm).toString(), it.activityInfo.packageName, isSys)
            }.sortedBy { it.label.lowercase() }
        apps = list
        ctx.dataStore.data.map { it[stringPreferencesKey("dock")]?.split(",") ?: listOf("com.android.dialer","com.android.mms","com.android.camera","com.whatsapp","com.spotify.music","com.google.android.youtube","com.activision.callofduty.shooter") }
            .collect { dock = it }
    }

    val filtered = remember(query, apps) { if (query.isBlank()) apps else apps.filter { it.label.contains(query, true) }.take(50) }

    MaterialTheme(colorScheme = if (gameMode) darkColorScheme(background = Color(0xFF0A0A0A)) else darkColorScheme(background = Color.Black)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (gameMode) "GAME MODE ON - ${apps.size} apps" else "${apps.size} applications", color = Color.Gray, fontSize = 12.sp)
                    Row { Text("GAME", color = if (gameMode) Color.Green else Color.Gray, fontSize = 12.sp); Switch(checked = gameMode, onCheckedChange = { gameMode = it }, modifier = Modifier.size(32.dp)) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Rechercher...") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                if (filtered.isEmpty() && query.length >= 2) {
                    Text("Chercher \"$query\" sur le web", color = Color(0xFF3B82F6), modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")))
                    })
                }
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filtered.size) { i ->
                        val app = filtered[i]
                        var showSheet by remember { mutableStateOf(false) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.combinedClickable(
                            onClick = {
                                if (app.pkg.contains("callofduty") && gameMode) {
                                    try { ctx.getSystemService(android.app.ActivityManager::class.java).let { am -> am.runningAppProcesses.forEach { p -> if (p.processName != app.pkg) try { am.killBackgroundProcesses(p.processName) } catch (_: Exception) {} } } } catch (_: Exception) {}
                                    try { ctx.sendBroadcast(Intent("com.miui.gamebooster.action.BOOST").apply { putExtra("package", app.pkg); putExtra("boost_mode", 1) }) } catch (_: Exception) {}
                                }
                                ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(app.pkg))
                            },
                            onLongClick = { showSheet = true }
                        )) {
                            Text(app.label.first().toString(), modifier = Modifier.size(56.dp).background(Color(0xFF1A1A1A), androidx.compose.foundation.shape.CircleShape).wrapContentSize(), color = Color.White)
                            Text(app.label, fontSize = 10.sp, color = Color(0xFF888888), maxLines = 1)
                        }
                        if (showSheet) {
                            ModalBottomSheet(onDismissRequest = { showSheet = false }) {
                                ListItem(headlineContent = { Text("Infos") }, modifier = Modifier.clickable {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${app.pkg}"))); showSheet = false
                                })
                                ListItem(headlineContent = { Text("Désinstaller", color = Color.Red) }, modifier = Modifier.clickable {
                                    ctx.startActivity(Intent(Intent.ACTION_DELETE).setData(Uri.parse("package:${app.pkg}"))); showSheet = false
                                })
                            }
                        }
                    }
                }
                Surface(color = Color(0xFF101010), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(7) { idx ->
                            val pkg = dock.getOrNull(idx)
                            val entry = apps.find { it.pkg == pkg }
                            if (entry != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                    ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(entry.pkg))
                                }) { Text(entry.label.first().toString(), modifier = Modifier.size(52.dp).background(Color(0xFF222222), androidx.compose.foundation.shape.CircleShape).wrapContentSize(), color = Color.White) }
                            } else {
                                Box(Modifier.size(52.dp).border(1.dp, Color.Gray, androidx.compose.foundation.shape.CircleShape).clickable {
                                    scope.launch { ctx.dataStore.edit { it[stringPreferencesKey("dock")] = (dock + (apps.firstOrNull()?.pkg ?: "")).take(7).joinToString(",") } }
                                }, contentAlignment = Alignment.Center) { Text("+", color = Color.Gray) }
                            }
                        }
                    }
                }
            }
        }
    }
}
