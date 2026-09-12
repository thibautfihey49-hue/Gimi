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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        try {
            window.setWindowAnimations(0)
            super.onCreate(savedInstanceState)
            setContent { NovaLiteApp() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        Bitmap.createScaledBitmap(bmp, 64, 64, false)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun NovaLiteApp() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()

    // ✅ Apps CHARGÉES UNE SEULE FOIS — reste en mémoire
    var allApps by remember { mutableStateOf(listOf<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var gameMode by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    
    // ✅ Dock SAUVEGARDÉ/CHARGÉ depuis DataStore
    var dock by remember { mutableStateOf(listOf<String>()) }
    var showDockEdit by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    val defaultDock = listOf(
        "com.android.dialer", "com.android.mms", "com.android.camera",
        "com.whatsapp", "com.spotify.music", "com.google.android.youtube",
        "com.activision.callofduty.shooter"
    )

    // ✅ Chargement UNE SEULE FOIS au démarrage
    LaunchedEffect(Unit) {
        if (initialized) return@LaunchedEffect
        initialized = true
        
        isLoading = true
        
        // Charger le dock sauvegardé
        try {
            val prefs = ctx.dataStore.data.first()
            val saved = prefs[stringPreferencesKey("dock")]
            dock = saved?.split(",")?.filter { it.isNotEmpty() } ?: defaultDock
        } catch (_: Exception) {
            dock = defaultDock
        }

        // Charger TOUTES les apps — UNE SEULE FOIS
        withContext(Dispatchers.IO) {
            try {
                val installedPkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
                
                // Charger les icônes en arrière-plan
                allApps.forEachIndexed { i, app ->
                    try {
                        val info = pm.getApplicationInfo(app.pkg, PackageManager.GET_META_DATA)
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

    // ✅ Sauvegarder le dock quand il change
    fun saveDock(newDock: List<String>) {
        dock = newDock
        scope.launch {
            try {
                ctx.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("dock")] = newDock.joinToString(",")
                }
            } catch (_: Exception) {}
        }
    }

    // ✅ Filtrer les apps
    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { it.label.lowercase().contains(query.lowercase()) }.take(30)
    }

    // ✅ Fond dégradé style launcher moderne
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F1729),
            Color(0xFF1A1F35),
            Color(0xFF12121F)
        )
    )

    MaterialTheme(colorScheme = darkColorScheme(background = Color.Transparent)) {
        Surface(Modifier.fillMaxSize(), brush = backgroundGradient) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    
                    // 📌 BARRE SUPÉRIEURE — Style moderne
                    Spacer(Modifier.height(40.dp))
                    
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Nova Lite",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    isLoading -> "Scan en cours..."
                                    gameMode -> "🎮 Mode jeu actif"
                                    else -> "${allApps.size} applications"
                                },
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(
                                checked = gameMode,
                                onCheckedChange = { gameMode = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00FF88),
                                    checkedTrackColor = Color(0xFF00FF88).copy(alpha = 0.3f),
                                    uncheckedThumbColor = Color(0xFF4B5563),
                                    uncheckedTrackColor = Color(0xFF1F2937)
                                )
                            )
                            IconButton(onClick = { showDockEdit = true }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Modifier le dock",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))

                    // 📌 BARRE DE RECHERCHE — Style moderne
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Rechercher une application...", fontSize = 14.sp, color = Color(0xFF6B7280)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1F2937).copy(alpha = 0.7f),
                            unfocusedContainerColor = Color(0xFF1F2937).copy(alpha = 0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color(0xFF00FF88)
                        ),
                        leadingIcon = {
                            Text("🔍", fontSize = 16.sp)
                        }
                    )

                    Spacer(Modifier.height(20.dp))

                    // 📌 GRILLE DES APPLICATIONS
                    if (isLoading && allApps.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = Color(0xFF00FF88),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Initialisation du lanceur...",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        if (filtered.isEmpty() && query.length >= 2) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E3A5F).copy(alpha = 0.4f))
                                    .clickable {
                                        ctx.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")
                                            )
                                        )
                                    }
                                    .padding(16.dp)
                            ) {
                                Text(
                                    "🔍 Rechercher \"$query\" sur le web",
                                    color = Color(0xFF60A5FA),
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            items(filtered.size, key = { filtered[it].pkg }) { i ->
                                val app = filtered[i]
                                var showSheet by remember { mutableStateOf(false) }

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
                                                val launchIntent = pm.getLaunchIntentForPackage(app.pkg)
                                                if (launchIntent != null) ctx.startActivity(launchIntent)
                                            } catch (_: Exception) {}
                                        },
                                        onLongClick = { showSheet = true }
                                    )
                                ) {
                                    // 📌 Icône avec ombre style moderne
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color(0xFF27272A))
                                            .then(
                                                if (app.icon != null) Modifier else Modifier
                                                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(16.dp))
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (app.icon != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = app.icon.asImageBitmap(),
                                                contentDescription = app.label,
                                                modifier = Modifier.size(38.dp),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                            )
                                        } else {
                                            Text(
                                                app.label.firstOrNull()?.toString() ?: "?",
                                                color = Color.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        app.label,
                                        fontSize = 10.sp,
                                        color = Color(0xFFD4D4D8),
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(60.dp)
                                    )
                                }

                                if (showSheet) {
                                    ModalBottomSheet(
                                        onDismissRequest = { showSheet = false },
                                        containerColor = Color(0xFF18181B),
                                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                                        scrimColor = Color.Black.copy(alpha = 0.5f)
                                    ) {
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp)
                                        ) {
                                            Text(
                                                text = app.label,
                                                color = Color.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(20.dp))
                                            
                                            // ✅ Ajouter au dock
                                            if (!dock.contains(app.pkg)) {
                                                ListItem(
                                                    headlineContent = { Text("📌 Ajouter au dock", color = Color.White) },
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(Color(0xFF27272A))
                                                        .clickable {
                                                            val newDock = dock + app.pkg
                                                            if (newDock.size <= 7) saveDock(newDock)
                                                            showSheet = false
                                                        }
                                                        .padding(16.dp)
                                                )
                                            }
                                            
                                            ListItem(
                                                headlineContent = { Text("ℹ️ Informations", color = Color.White) },
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color(0xFF27272A))
                                                    .clickable {
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
                                                    .padding(16.dp)
                                            )
                                            
                                            ListItem(
                                                headlineContent = { Text("🗑️ Désinstaller", color = Color(0xFFF87171)) },
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color(0xFF27272A))
                                                    .clickable {
                                                        try {
                                                            ctx.startActivity(
                                                                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
                                                            )
                                                        } catch (_: Exception) {}
                                                        showSheet = false
                                                    }
                                                    .padding(16.dp)
                                            )
                                            
                                            Spacer(Modifier.height(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 📌 DOCK EN BAS — Style flou moderne
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Fond flou
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF18181B).copy(alpha = 0.8f))
                            .then(
                                Modifier.border(
                                    1.dp,
                                    Color(0xFF3F3F46).copy(alpha = 0.5f),
                                    RoundedCornerShape(24.dp)
                                )
                            )
                    )
                    
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(7) { idx ->
                            val pkg = dock.getOrNull(idx)
                            val entry = allApps.find { it.pkg == pkg }

                            if (entry != null) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF27272A))
                                        .clickable {
                                            try {
                                                val launchIntent = pm.getLaunchIntentForPackage(entry.pkg)
                                                if (launchIntent != null) ctx.startActivity(launchIntent)
                                            } catch (_: Exception) {}
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (entry.icon != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = entry.icon.asImageBitmap(),
                                            contentDescription = entry.label,
                                            modifier = Modifier.size(34.dp),
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
                                        .size(50.dp)
                                        .border(
                                            1.dp,
                                            Color(0xFF52525B),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .clickable { showDockEdit = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+", color = Color(0xFF71717A), fontSize = 20.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 📌 MODALE DE MODIFICATION DU DOCK
            if (showDockEdit) {
                AlertDialog(
                    containerColor = Color(0xFF18181B),
                    shape = RoundedCornerShape(24.dp),
                    title = {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Modifier le dock", color = Color.White, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showDockEdit = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Fermer", tint = Color(0xFF9CA3AF))
                            }
                        }
                    },
                    text = {
                        Column {
                            Text(
                                "Maintiens une app pour la supprimer du dock",
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            
                            // Apps actuellement dans le dock
                            dock.forEachIndexed { idx, pkg ->
                                val app = allApps.find { it.pkg == pkg }
                                if (app != null) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF27272A))
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = {
                                                    val newDock = dock.toMutableList().apply { removeAt(idx) }
                                                    saveDock(newDock)
                                                }
                                            )
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFF3F3F46)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (app.icon != null) {
                                                    androidx.compose.foundation.Image(
                                                        bitmap = app.icon.asImageBitmap(),
                                                        contentDescription = app.label,
                                                        modifier = Modifier.size(24.dp),
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                                    )
                                                } else {
                                                    Text(
                                                        app.label.firstOrNull()?.toString() ?: "?",
                                                        color = Color.White,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Text(app.label, color = Color.White, fontSize = 14.sp)
                                        }
                                        Text("×", color = Color(0xFFF87171), fontSize = 18.sp)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            
                            if (dock.size < 7) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "💡 Astuce : Ouvre une application → Menu long → Ajouter au dock",
                                    color = Color(0xFF60A5FA),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDockEdit = false }) {
                            Text("Fermer", color = Color(0xFF00FF88))
                        }
                    }
                )
            }
        }
    }
}
