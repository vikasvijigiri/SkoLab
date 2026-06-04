package com.company.skolab.auth

import android.content.Context
import android.util.Log
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.company.skolab.BuildConfig
import com.company.skolab.R
import com.company.skolab.model.SkoLabUser
import com.company.skolab.model.UserConnection
import kotlinx.coroutines.tasks.await

import com.company.skolab.data.UserPreferences
import kotlinx.coroutines.flow.firstOrNull

class AuthManager(private val context: Context) {
    private val auth = Firebase.auth
    private val db = Firebase.firestore
    private val credentialManager = CredentialManager.create(context)
    private val userPrefs = UserPreferences(context)

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    /**
     * True when the user is authenticated via Firebase (synchronous, no blocking).
     * For guest sign-in state, use [isSignedInAsync] or observe [userPrefs.isGuestSignedIn].
     */
    val isSignedIn: Boolean
        get() = currentUser != null

    /**
     * Async version that also checks the guest flag in DataStore.
     * Use this at app startup where a coroutine context is available.
     */
    suspend fun isSignedInAsync(): Boolean {
        return currentUser != null || (userPrefs.isGuestSignedIn.firstOrNull() ?: false)
    }

    val cachedUser = userPrefs.cachedUser

    private fun resolveWebClientId(): String? {
        val fromBuild = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (fromBuild.isNotEmpty() && !fromBuild.contains("xxxx")) {
            return fromBuild
        }
        val fromResources = context.getString(R.string.default_web_client_id).trim()
        if (fromResources.isNotEmpty() && !fromResources.contains("YOUR_WEB_CLIENT")) {
            return fromResources
        }
        return null
    }

    fun getWebClientId(): String? {
        return resolveWebClientId()
    }

    private fun Context.findActivity(): Context {
        var currentContext = this
        while (currentContext is android.content.ContextWrapper) {
            if (currentContext is android.app.Activity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
        return this
    }

    suspend fun initiateGoogleSignIn(callerContext: Context): Result<FirebaseUser?> {
        val webClientId = resolveWebClientId()
        if (webClientId == null) {
            return attemptAnonymousFallback(IllegalStateException("Google Sign-In is not configured (missing client ID)."))
        }

        // First try: filter by authorized accounts (fast, silent)
        // If that finds nothing, fall back to showing the full account picker
        return try {
            val result = tryGetCredential(callerContext, webClientId, filterByAuthorized = true)
            handleSignInResult(result.credential)
        } catch (e: NoCredentialException) {
            // No previously authorized account — show full account picker
            Log.d("AuthManager", "No authorized account found, showing full picker")
            try {
                val result = tryGetCredential(callerContext, webClientId, filterByAuthorized = false)
                handleSignInResult(result.credential)
            } catch (e2: Exception) {
                Log.e("AuthManager", "Google Sign-In account picker failed or cancelled", e2)
                attemptAnonymousFallback(e2)
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Google Sign-In flow failed", e)
            attemptAnonymousFallback(e)
        }
    }

    suspend fun attemptAnonymousFallback(originalError: Exception): Result<FirebaseUser?> {
        Log.w("AuthManager", "Attempting anonymous bypass due to Google Sign-In error: ${originalError.message}")
        return try {
            val authResult = auth.signInAnonymously().await()
            val user = authResult.user
            if (user != null) {
                saveUserToFirestore(user)
                Result.success(user)
            } else {
                localBypass()
            }
        } catch (anonEx: Exception) {
            Log.e("AuthManager", "Anonymous bypass failed, executing local guest bypass", anonEx)
            localBypass()
        }
    }

    private suspend fun localBypass(): Result<FirebaseUser?> {
        Log.i("AuthManager", "Executing local guest bypass: caching local guest credentials")
        userPrefs.setGuestSignedIn(true)
        userPrefs.cacheUser(
            SkoLabUser(
                uid = "guest_user_" + System.currentTimeMillis(),
                name = "SkoLab User",
                email = "guest@example.com",
                researchFocus = "",
                complexityScore = 0.5f,
                savedPapers = emptyList()
            )
        )
        return Result.success(null)
    }


    private suspend fun tryGetCredential(
        callerContext: Context,
        webClientId: String,
        filterByAuthorized: Boolean
    ): androidx.credentials.GetCredentialResponse {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)   // always show picker — avoids silent failures
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val activityContext = callerContext.findActivity()
        return credentialManager.getCredential(context = activityContext, request = request)
    }

    private fun friendlyError(e: Exception): Exception {
        val msg = e.message ?: ""
        return when {
            msg.contains("DEVELOPER_ERROR") ->
                Exception("Auth configuration error. Check SHA-1 fingerprint in Firebase console.")
            msg.contains("network") || msg.contains("offline") ->
                Exception("No internet connection. Please check your network and try again.")
            msg.contains("cancel") ->
                Exception("Sign-in cancelled")
            else -> e
        }
    }

    private suspend fun handleSignInResult(credential: Credential): Result<FirebaseUser?> {
        return try {
            when (credential.type) {
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    signInWithGoogle(googleIdTokenCredential.idToken)
                }
                else -> Result.failure(Exception("Unsupported credential type: ${credential.type}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser?> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user
            if (user != null) {
                // Save to Firestore in background — don't block or fail sign-in if offline
                saveUserToFirestore(user)
                Result.success(user)
            } else {
                Result.failure(Exception("Sign-in completed but user is null"))
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Firebase credential sign-in failed", e)
            Result.failure(e)
        }
    }

    private suspend fun saveUserToFirestore(user: FirebaseUser) {
        try {
            // Try cache first — avoids offline error on initial sign-in with no network
            val userDoc = try {
                db.collection("researchers").document(user.uid).get(Source.CACHE).await()
            } catch (cacheEx: Exception) {
                // Cache miss — attempt server fetch
                db.collection("researchers").document(user.uid).get(Source.SERVER).await()
            }

            val userData = if (userDoc.exists()) {
                val existing = userDoc.toObject(SkoLabUser::class.java)
                db.collection("researchers").document(user.uid).update(
                    "isOnline", true,
                    "emailVerified", user.isEmailVerified
                )
                existing?.copy(isOnline = true, emailVerified = user.isEmailVerified)
            } else {
                val newUserData = SkoLabUser(
                    uid = user.uid,
                    name = user.displayName ?: "",
                    email = user.email ?: "",
                    researchFocus = "",
                    isOnline = true,
                    emailVerified = user.isEmailVerified
                )
                db.collection("researchers").document(user.uid).set(newUserData)
                newUserData
            }
            if (userData != null) {
                userPrefs.cacheUser(userData)
            }
        } catch (e: Exception) {
            // Firestore offline — not fatal. Cache basic user info from Firebase Auth instead
            Log.w("AuthManager", "Firestore unavailable during sign-in, using Auth data as fallback", e)
            val fallback = SkoLabUser(
                uid = user.uid,
                name = user.displayName ?: "",
                email = user.email ?: "",
                researchFocus = "",
                isOnline = true,
                emailVerified = user.isEmailVerified
            )
            userPrefs.cacheUser(fallback)
        }
    }

    suspend fun setUserOnlineStatus(online: Boolean) {
        val user = currentUser ?: return
        try {
            db.collection("researchers").document(user.uid).update("isOnline", online)
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to update online status", e)
        }
    }

    suspend fun getUserData(uid: String): SkoLabUser? {
        // Return cached data immediately if available — avoids Firestore offline error in UI
        val cached = userPrefs.cachedUser.firstOrNull()
        if (cached != null && cached.uid == uid) return cached

        return try {
            // Try cache first, then server
            val doc = try {
                db.collection("researchers").document(uid).get(Source.CACHE).await()
            } catch (cacheEx: Exception) {
                db.collection("researchers").document(uid).get(Source.SERVER).await()
            }
            val userData = doc.toObject(SkoLabUser::class.java)
            if (userData != null) {
                userPrefs.cacheUser(userData)
            }
            userData
        } catch (e: Exception) {
            Log.w("AuthManager", "Error getting user data (offline?), returning cached if available", e)
            userPrefs.cachedUser.firstOrNull() // return local cache rather than null
        }
    }

    suspend fun updateUserProfile(name: String, focus: String) {
        val user = currentUser
        if (user != null) {
            try {
                db.collection("researchers").document(user.uid).update(
                    "name", name,
                    "researchFocus", focus
                )
            } catch (e: Exception) {
                Log.w("AuthManager", "Failed to update user profile on Firestore", e)
            }
        }
        val cached = userPrefs.cachedUser.firstOrNull()
        if (cached != null) {
            userPrefs.cacheUser(cached.copy(name = name, researchFocus = focus))
        }
    }

    suspend fun updateAcademicProfile(name: String, focus: String, academicStatus: String, about: String) {
        val user = currentUser
        if (user != null) {
            try {
                db.collection("researchers").document(user.uid).update(
                    "name", name,
                    "researchFocus", focus,
                    "academicStatus", academicStatus,
                    "about", about
                )
            } catch (e: Exception) {
                Log.w("AuthManager", "Failed to update academic profile on Firestore", e)
            }
        }
        val cached = userPrefs.cachedUser.firstOrNull()
        if (cached != null) {
            userPrefs.cacheUser(cached.copy(
                name = name,
                researchFocus = focus,
                academicStatus = academicStatus,
                about = about
            ))
        }
    }

    suspend fun updateCv(uri: String, fileName: String) {
        val user = currentUser
        if (user != null) {
            try {
                db.collection("researchers").document(user.uid).update(
                    "cvUri", uri,
                    "cvFileName", fileName
                )
            } catch (e: Exception) {
                Log.w("AuthManager", "Failed to update CV on Firestore", e)
            }
        }
        val cached = userPrefs.cachedUser.firstOrNull()
        if (cached != null) {
            userPrefs.cacheUser(cached.copy(cvUri = uri, cvFileName = fileName))
        }
    }


    suspend fun updateUserResearchFocus(focus: String) {
        val user = currentUser ?: return
        try {
            // Update Firestore
            db.collection("researchers").document(user.uid).update("researchFocus", focus)
            // Update local cache
            val cached = userPrefs.cachedUser.firstOrNull()
            if (cached != null) {
                userPrefs.cacheUser(cached.copy(researchFocus = focus))
            }
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to update research focus", e)
        }
    }

    suspend fun addConnectionToFirestore(connection: UserConnection) {
        val user = currentUser ?: return
        try {
            val cleanId = connection.id.substringAfterLast("/")
            db.collection("researchers")
                .document(user.uid)
                .collection("connections")
                .document(cleanId)
                .set(connection)
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to sync connection to Firestore, queuing offline", e)
        }
    }

    suspend fun removeConnectionFromFirestore(connectionId: String) {
        val user = currentUser ?: return
        try {
            val cleanId = connectionId.substringAfterLast("/")
            db.collection("researchers")
                .document(user.uid)
                .collection("connections")
                .document(cleanId)
                .delete()
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to sync connection deletion to Firestore", e)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser?> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                // Fetch user data from Firestore and cache it
                val userData = getUserData(user.uid)
                if (userData == null) {
                    val newUserData = SkoLabUser(
                        uid = user.uid,
                        name = user.displayName ?: email.substringBefore("@"),
                        email = user.email ?: email,
                        researchFocus = "",
                        isOnline = true
                    )
                    db.collection("researchers").document(user.uid).set(newUserData)
                    userPrefs.cacheUser(newUserData)
                } else {
                    db.collection("researchers").document(user.uid).update("isOnline", true)
                }
                Result.success(user)
            } else {
                Result.failure(Exception("Authentication succeeded but user is null"))
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Email sign-in failed", e)
            Result.failure(e)
        }
    }

    suspend fun registerWithEmail(email: String, password: String, name: String, discipline: String): Result<FirebaseUser?> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                // Update profile display name
                val profileUpdates = com.google.firebase.auth.userProfileChangeRequest {
                    displayName = name
                }
                user.updateProfile(profileUpdates).await()

                // Create Firestore document
                val newUserData = SkoLabUser(
                    uid = user.uid,
                    name = name,
                    email = email,
                    researchFocus = discipline,
                    isOnline = true
                )
                db.collection("researchers").document(user.uid).set(newUserData)
                userPrefs.cacheUser(newUserData)

                Result.success(user)
            } else {
                Result.failure(Exception("Registration succeeded but user is null"))
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Email registration failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        val user = currentUser
        if (user != null) {
            try {
                db.collection("researchers").document(user.uid).update("isOnline", false)
            } catch (e: Exception) {
                Log.w("AuthManager", "Failed to set status offline on signout", e)
            }
        }
        auth.signOut()
        userPrefs.clearCachedUser()
        try {
            db.clearPersistence()
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to clear persistence on signout", e)
        }
    }
}
