// file: app/src/main/java/com/example/appadmin/MainActivity.kt
package com.example.appadmin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.appadmin.components.AdminLoginScreen
import com.example.appadmin.model.Event
import com.example.appadmin.navigation.AdminNavGraph
import com.example.appadmin.ui.theme.AdminTheme
import com.example.appadmin.viewmodel.AdminViewModel
import com.example.appadmin.viewmodel.AdminViewModelFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private val vm: AdminViewModel by viewModels {
        AdminViewModelFactory(
            FirebaseAuth.getInstance(),
            FirebaseFirestore.getInstance()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ Décommente uniquement pour forcer la déconnexion à chaque lancement (debug)
        // FirebaseAuth.getInstance().signOut()

        setContent {
            AdminTheme {
                Surface(color = MaterialTheme.colorScheme.background) {

                    val nav = rememberNavController()
                    val events by vm.events.collectAsState()
                    val counts by vm.checkinsCount.collectAsState()
                    val regs by vm.registrationsCount.collectAsState()  // 👈 NEW
                    val editing by vm.editing.collectAsState()
                    val scope = rememberCoroutineScope()

                    var selectedEventForCode by remember { mutableStateOf<Event?>(null) }

                    // ---- Auth / Gate Admin ----
                    val auth = remember { FirebaseAuth.getInstance() }
                    var currentUser by remember { mutableStateOf(auth.currentUser) }
                    var isLoading by remember { mutableStateOf(true) }
                    var isAdmin by remember { mutableStateOf(false) }

                    // Écoute l'état d'auth pour réagir aux connexions/déconnexions
                    DisposableEffect(Unit) {
                        val listener = FirebaseAuth.AuthStateListener { fb ->
                            currentUser = fb.currentUser
                        }
                        auth.addAuthStateListener(listener)
                        onDispose { auth.removeAuthStateListener(listener) }
                    }

                    // Recalcule le rôle admin quand l’utilisateur change
                    LaunchedEffect(currentUser) {
                        isLoading = true
                        val user = currentUser
                        if (user == null) {
                            isAdmin = false
                        } else {
                            isAdmin = try {
                                val snap = FirebaseFirestore.getInstance()
                                    .collection("users")
                                    .document(user.uid)
                                    .get()
                                    .await()
                                snap.exists() && snap.getString("role") == "admin"
                            } catch (_: Exception) {
                                false
                            }
                        }
                        isLoading = false
                    }

                    // ✅ DÉCLENCHE l’écoute Firestore seulement si admin
                    LaunchedEffect(isAdmin) {
                        if (isAdmin) {
                            vm.startListening()
                        } else {
                            vm.stopListening()
                        }
                    }

                    when {
                        isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        !isAdmin -> {
                            // Page Login/Signup Admin (email/password)
                            AdminLoginScreen(
                                onAuthenticatedAsAdmin = {
                                    // Rien à faire : l’AuthStateListener relancera le calcul
                                }
                            )
                        }
                        else -> {
                            // Back-office Admin
                            AdminNavGraph(
                                nav = nav,
                                events = events,
                                checkinsCount = counts,
                                editing = editing,
                                registrationsCount = regs,
                                onCreateClick = { vm.startEditing(null) },
                                onEditClick = { e -> vm.startEditing(e) },
                                onDeleteClick = { id -> vm.deleteEvent(id) },
                                onSaveEvent = { ev, onResult -> vm.createOrUpdateEvent(ev, onResult) },
                                onScanResult = { token, pushMsg ->
                                    scope.launch {
                                        val msg = vm.checkInFromToken(token)
                                        pushMsg(msg)
                                    }
                                },
                                onCodeValidate = { eventId, code, pushMsg ->
                                    scope.launch {
                                        val msg = vm.checkInByCode(eventId, code)
                                        pushMsg(msg)
                                    }
                                },
                                onPickEventForCode = { selectedEventForCode = it },
                                selectedEventForCode = selectedEventForCode
                            )
                        }
                    }
                }
            }
        }
    }
}
