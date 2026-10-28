package com.lockscreen.videowake

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        const val RC_SIGN_IN = 9001
        // Web Client ID from google-services.json
        const val WEB_CLIENT_ID = "954237427339-p4aqjq8bv345vggv091t23a4tbf7gb41.apps.googleusercontent.com"
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private var assignmentListener: ListenerRegistration? = null

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isUserSignedIn: Boolean
        get() = auth.currentUser != null

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    suspend fun handleSignInResult(data: Intent?): Result<FirebaseUser> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
                ?: return Result.failure(Exception("Google Sign-In returned empty account"))

            val idToken = account.idToken
                ?: return Result.failure(Exception("Google ID Token is missing"))

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val user = authResult.user ?: return Result.failure(Exception("Firebase user is null"))

            // Sync user profile and FCM token to Firestore
            syncUserProfileToFirestore(user)

            // Subscribe to global wallpaper notifications
            FirebaseMessaging.getInstance().subscribeToTopic("all_users")

            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            Result.failure(e)
        }
    }

    suspend fun syncUserProfileToFirestore(user: FirebaseUser) {
        try {
            var fcmToken = ""
            try {
                fcmToken = FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get FCM token", e)
            }

            val userMap = mutableMapOf<String, Any>(
                "uid" to user.uid,
                "email" to (user.email ?: ""),
                "displayName" to (user.displayName ?: ""),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "lastActiveAt" to System.currentTimeMillis()
            )
            if (fcmToken.isNotBlank()) {
                userMap["fcmToken"] = fcmToken
            }

            firestore.collection("users").document(user.uid)
                .set(userMap, SetOptions.merge())
                .await()
            Log.d(TAG, "User profile synced to Firestore: ${user.email}")
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing user to Firestore", e)
        }
    }

    fun listenToAssignedWallpapers(onUpdated: (List<String>) -> Unit) {
        val user = auth.currentUser ?: return
        assignmentListener?.remove()
        assignmentListener = firestore.collection("users").document(user.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Listen to assignments failed: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val assigned = snapshot.get("assignedWallpapers") as? List<String> ?: emptyList()
                    onUpdated(assigned)
                }
            }
    }

    fun signOut(onComplete: () -> Unit) {
        assignmentListener?.remove()
        assignmentListener = null
        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            onComplete()
        }
    }
}
