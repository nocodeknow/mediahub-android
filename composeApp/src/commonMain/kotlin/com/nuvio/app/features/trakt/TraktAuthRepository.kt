package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

object TraktAuthRepository : TrackingAuthProvider {
    const val DEFAULT_TRAKT_CLIENT_ID = "GG_HeGCytpTZ3yzFxdZ-bnwMgBPzpE0_u8YkQoyM9Lw"
    const val DEFAULT_VERIFICATION_URL = "https://auth.trakt.tv/activate"
    private const val BASE_URL = "https://api.trakt.tv"
    private const val API_VERSION = "2"

    private val log = Logger.withTag("TraktAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private var activePollingJob: Job? = null

    private val _uiState = MutableStateFlow(TraktAuthUiState())
    val uiState: StateFlow<TraktAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.TRAKT,
        displayName = "Trakt",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.COMMENTS,
            TrackingCapability.RECOMMENDATIONS,
        ),
    )

    init {
        TrackingProviderRegistry.register(this)
    }

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var profileGeneration: Long = 0L
    private var authState = TraktAuthState()

    override fun ensureLoaded() {
        ensureLoaded(ProfileRepository.activeProfileId)
    }

    override fun onProfileChanged() {
        onProfileChanged(ProfileRepository.activeProfileId)
    }

    fun ensureLoaded(profileId: Int) {
        if (hasLoaded && currentProfileId == profileId) return
        loadFromDisk(profileId)
    }

    fun onProfileChanged(profileId: Int) {
        activePollingJob?.cancel()
        activePollingJob = null
        loadFromDisk(profileId)
    }

    override fun clearLocalState() {
        activePollingJob?.cancel()
        activePollingJob = null
        TraktWatchedShowSnapshotRepository.clear()
        hasLoaded = false
        currentProfileId = 1
        profileGeneration += 1L
        authState = TraktAuthState()
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        if (currentProfileId == profileId) {
            activePollingJob?.cancel()
            activePollingJob = null
        }
        TraktAuthStorage.removeProfile(profileId)
    }

    fun snapshot(profileId: Int = ProfileRepository.activeProfileId): TraktAuthUiState {
        ensureLoaded(profileId)
        return _uiState.value
    }

    fun getEffectiveClientId(profileId: Int = currentProfileId): String {
        ensureLoaded(profileId)
        return authState.customClientId?.trim()?.ifBlank { null }
            ?: TraktConfig.CLIENT_ID.trim().ifBlank { null }
            ?: DEFAULT_TRAKT_CLIENT_ID
    }

    fun setCustomClientId(clientId: String, profileId: Int = currentProfileId) {
        ensureLoaded(profileId)
        val trimmed = clientId.trim().ifBlank { null }
        authState = authState.copy(customClientId = trimmed)
        persist(profileId)
        publish()
    }

    fun hasRequiredCredentials(): Boolean = getEffectiveClientId().isNotBlank()

    suspend fun onConnectRequested(profileId: Int = ProfileRepository.activeProfileId): String? {
        ensureLoaded(profileId)
        activePollingJob?.cancel()
        activePollingJob = null

        val clientId = getEffectiveClientId(profileId)
        if (clientId.isBlank()) {
            publish(errorMessage = localizedString(Res.string.trakt_missing_credentials))
            return null
        }

        val targetProfileId = profileId
        val targetGeneration = profileGeneration
        publish(isLoading = true, errorMessage = null, statusMessage = null)

        val body = json.encodeToString(
            TraktDeviceCodeRequest(clientId = clientId)
        )

        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth/device/code",
                body = body,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "trakt-api-version" to API_VERSION,
                    "trakt-api-key" to clientId,
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to initiate Trakt device code: ${error.message}" }
        }.getOrNull()

        if (currentProfileId != targetProfileId || profileGeneration != targetGeneration) {
            return null
        }

        if (response == null || response.status !in 200..299) {
            publish(
                isLoading = false,
                errorMessage = localizedString(Res.string.trakt_sign_in_complete_failed),
            )
            return null
        }

        val parsed = runCatching {
            json.decodeFromString<TraktDeviceCodeResponse>(response.body)
        }.onFailure {
            log.w { "Failed to parse Trakt device code response: ${it.message}" }
        }.getOrNull()

        if (parsed == null) {
            publish(
                isLoading = false,
                errorMessage = localizedString(Res.string.trakt_invalid_token_response),
            )
            return null
        }

        val now = TraktPlatformClock.nowEpochMs()
        val expiresAtMillis = now + (parsed.expiresIn * 1000L)

        authState = authState.copy(
            deviceCode = parsed.deviceCode,
            userCode = parsed.userCode,
            verificationUrl = parsed.verificationUrl,
            intervalSeconds = parsed.interval,
            deviceCodeExpiresAtMillis = expiresAtMillis,
            pendingAuthorizationStartedAtMillis = now,
        )
        persist(targetProfileId)
        publish(
            isLoading = false,
            statusMessage = "Enter code ${parsed.userCode} at ${parsed.verificationUrl}",
            errorMessage = null,
        )

        activePollingJob = scope.launch {
            pollDeviceTokenLoop(
                targetProfileId = targetProfileId,
                targetGeneration = targetGeneration,
                deviceCode = parsed.deviceCode,
                initialInterval = parsed.interval,
                expiresAtMillis = expiresAtMillis,
            )
        }

        return parsed.verificationUrl
    }

    private suspend fun pollDeviceTokenLoop(
        targetProfileId: Int,
        targetGeneration: Long,
        deviceCode: String,
        initialInterval: Int,
        expiresAtMillis: Long,
    ) {
        var pollIntervalSeconds = maxOf(initialInterval, 1)
        val maxPollDurationMs = 15 * 60 * 1000L
        val loopStartedAt = TraktPlatformClock.nowEpochMs()

        while (currentCoroutineContext().isActive) {
            delay(pollIntervalSeconds * 1000L)
            if (!currentCoroutineContext().isActive) break

            // Guard: profile switched or state reset
            if (currentProfileId != targetProfileId || profileGeneration != targetGeneration) break

            // Guard: cancelled or authenticated
            if (authState.deviceCode != deviceCode || authState.isAuthenticated) break

            val now = TraktPlatformClock.nowEpochMs()
            if (now >= expiresAtMillis || (now - loopStartedAt) >= maxPollDurationMs) {
                clearDeviceCode()
                persist(targetProfileId)
                publish(
                    isLoading = false,
                    errorMessage = "Sign-in code expired. Please restart the sign-in process.",
                )
                break
            }

            val clientId = getEffectiveClientId(targetProfileId)
            val body = json.encodeToString(
                TraktDeviceTokenRequest(
                    code = deviceCode,
                    clientId = clientId,
                )
            )

            val response = runCatching {
                httpRequestRaw(
                    method = "POST",
                    url = "$BASE_URL/oauth/device/token",
                    body = body,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "trakt-api-version" to API_VERSION,
                        "trakt-api-key" to clientId,
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Trakt device token poll network failure: ${error.message}" }
            }.getOrNull()

            if (!currentCoroutineContext().isActive) break
            if (currentProfileId != targetProfileId || profileGeneration != targetGeneration) break
            if (authState.deviceCode != deviceCode || authState.isAuthenticated) break

            if (response == null) {
                continue
            }

            when (classifyTraktDeviceTokenStatus(response.status)) {
                TraktDeviceTokenPollResult.SUCCESS -> {
                    val parsed = runCatching {
                        json.decodeFromString<TraktTokenResponse>(response.body)
                    }.getOrNull()

                    if (parsed != null) {
                        authState = authState.copy(
                            accessToken = parsed.accessToken,
                            refreshToken = parsed.refreshToken,
                            tokenType = parsed.tokenType,
                            createdAt = parsed.createdAt,
                            expiresIn = parsed.expiresIn,
                            deviceCode = null,
                            userCode = null,
                            verificationUrl = null,
                            intervalSeconds = null,
                            deviceCodeExpiresAtMillis = null,
                            pendingAuthorizationStartedAtMillis = null,
                            pendingAuthorizationState = null,
                        )
                        persist(targetProfileId)
                        refreshUserSettings(targetProfileId)
                        publish(
                            isLoading = false,
                            statusMessage = localizedString(Res.string.trakt_connected_status),
                            errorMessage = null,
                        )
                    } else {
                        clearDeviceCode()
                        persist(targetProfileId)
                        publish(
                            isLoading = false,
                            errorMessage = localizedString(Res.string.trakt_invalid_token_response),
                        )
                    }
                    break
                }
                TraktDeviceTokenPollResult.PENDING -> {
                    // Continue polling next cycle
                }
                TraktDeviceTokenPollResult.SLOW_DOWN -> {
                    pollIntervalSeconds += 5
                }
                TraktDeviceTokenPollResult.EXPIRED -> {
                    clearDeviceCode()
                    persist(targetProfileId)
                    publish(
                        isLoading = false,
                        errorMessage = "Sign-in code expired. Please restart the sign-in process.",
                    )
                    break
                }
                TraktDeviceTokenPollResult.DENIED -> {
                    clearDeviceCode()
                    persist(targetProfileId)
                    publish(
                        isLoading = false,
                        errorMessage = localizedString(Res.string.trakt_authorization_denied),
                    )
                    break
                }
                TraktDeviceTokenPollResult.INVALID_CODE -> {
                    clearDeviceCode()
                    persist(targetProfileId)
                    publish(
                        isLoading = false,
                        errorMessage = "Invalid authorization code.",
                    )
                    break
                }
                TraktDeviceTokenPollResult.ALREADY_USED -> {
                    clearDeviceCode()
                    persist(targetProfileId)
                    publish(
                        isLoading = false,
                        errorMessage = "Authorization code was already used.",
                    )
                    break
                }
                TraktDeviceTokenPollResult.TRANSIENT_ERROR -> {
                    // Transient error, continue polling
                }
            }
        }
    }

    fun pendingAuthorizationUrl(profileId: Int = ProfileRepository.activeProfileId): String? {
        ensureLoaded(profileId)
        return authState.verificationUrl?.takeIf(String::isNotBlank)
            ?: if (!authState.deviceCode.isNullOrBlank()) DEFAULT_VERIFICATION_URL else null
    }

    fun onCancelAuthorization(profileId: Int = ProfileRepository.activeProfileId) {
        ensureLoaded(profileId)
        activePollingJob?.cancel()
        activePollingJob = null
        clearDeviceCode()
        persist(profileId)
        publish(statusMessage = null, errorMessage = null, isLoading = false)
    }

    fun onCancelDeviceFlow(profileId: Int = ProfileRepository.activeProfileId) {
        onCancelAuthorization(profileId)
    }

    fun onAuthLaunchFailed(reason: String) {
        publish(errorMessage = reason)
    }

    fun onAuthCallbackReceived(callbackUrl: String) {
        // No-op for direct device flow
    }

    override fun handleAuthCallback(url: String): Boolean = false

    suspend fun authorizedHeaders(profileId: Int = currentProfileId): Map<String, String>? {
        ensureLoaded(profileId)
        if (!authState.isAuthenticated) return null

        val hasValidToken = refreshTokenIfNeeded(force = false, profileId = profileId)
        if (!hasValidToken) return null

        val accessToken = authState.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) return null

        return mapOf(
            "trakt-api-version" to API_VERSION,
            "trakt-api-key" to getEffectiveClientId(profileId),
            "Authorization" to "Bearer $accessToken",
        )
    }

    suspend fun refreshUserSettings(profileId: Int = currentProfileId): String? {
        ensureLoaded(profileId)
        val headers = authorizedHeaders(profileId) ?: return null
        val response = runCatching {
            httpGetTextWithHeaders(
                url = "$BASE_URL/users/settings",
                headers = headers,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Trakt user settings: ${error.message}" }
        }.getOrNull() ?: return null

        val parsed = runCatching {
            json.decodeFromString<TraktUserSettingsResponse>(response)
        }.getOrNull() ?: return null

        authState = authState.copy(
            username = parsed.user?.username,
            userSlug = parsed.user?.ids?.slug,
        )
        persist(profileId)
        publish()
        return authState.username
    }

    fun onDisconnectRequested(profileId: Int = currentProfileId) {
        ensureLoaded(profileId)
        activePollingJob?.cancel()
        activePollingJob = null
        scope.launch {
            disconnect(profileId)
        }
    }

    private suspend fun disconnect(profileId: Int = currentProfileId) {
        publish(isLoading = true, errorMessage = null)

        val token = authState.accessToken?.takeIf { it.isNotBlank() }
        val clientId = getEffectiveClientId(profileId)
        if (!token.isNullOrBlank() && clientId.isNotBlank()) {
            val body = json.encodeToString(
                TraktRevokeRequest(
                    token = token,
                    clientId = clientId,
                ),
            )
            runCatching {
                httpRequestRaw(
                    method = "POST",
                    url = "$BASE_URL/oauth/revoke",
                    body = body,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "trakt-api-version" to API_VERSION,
                        "trakt-api-key" to clientId,
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to revoke Trakt token: ${error.message}" }
            }
        }

        // Clear session tokens but preserve customClientId
        authState = TraktAuthState(customClientId = authState.customClientId)
        persist(profileId)
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_disconnected_status),
            errorMessage = null,
        )
    }

    private suspend fun refreshTokenIfNeeded(force: Boolean, profileId: Int = currentProfileId): Boolean = refreshMutex.withLock {
        val clientId = getEffectiveClientId(profileId)
        if (clientId.isBlank()) return@withLock false
        val refreshToken = authState.refreshToken?.takeIf { it.isNotBlank() }
            ?: return@withLock false

        if (!force && !isTokenExpiredOrExpiring(authState)) {
            return@withLock true
        }

        val body = json.encodeToString(
            TraktRefreshTokenRequest(
                refreshToken = refreshToken,
                clientId = clientId,
            ),
        )

        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "trakt-api-version" to API_VERSION,
                    "trakt-api-key" to clientId,
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Trakt token refresh transport failure: ${error.message}" }
        }.getOrNull() ?: return@withLock false

        if (ProfileRepository.activeProfileId != profileId || authState.refreshToken != refreshToken) {
            return@withLock false
        }

        when (traktTokenRefreshResponseAction(response.status)) {
            TraktTokenRefreshResponseAction.INVALIDATE -> {
                log.w { "Trakt rejected the refresh token with HTTP 400; clearing local credentials" }
                invalidateCredentials(profileId)
                return@withLock false
            }

            TraktTokenRefreshResponseAction.TRANSIENT_FAILURE -> {
                log.w { "Trakt token refresh failed with HTTP ${response.status}" }
                return@withLock false
            }

            TraktTokenRefreshResponseAction.ACCEPT -> Unit
        }

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response.body)
        }.getOrNull() ?: return@withLock false

        authState = authState.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            tokenType = parsed.tokenType,
            createdAt = parsed.createdAt,
            expiresIn = parsed.expiresIn,
        )
        persist(profileId)
        publish()
        true
    }

    private fun invalidateCredentials(profileId: Int) {
        activePollingJob?.cancel()
        activePollingJob = null
        authState = TraktAuthState(customClientId = authState.customClientId)
        persist(profileId)
        publish(
            isLoading = false,
            statusMessage = null,
            errorMessage = localizedString(Res.string.trakt_authorization_expired_reconnect),
        )
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        profileGeneration += 1L
        hasLoaded = true
        val payload = TraktAuthStorage.loadPayload(profileId).orEmpty().trim()
        authState = if (payload.isBlank()) {
            TraktAuthState()
        } else {
            runCatching { json.decodeFromString<TraktAuthState>(payload) }
                .getOrElse {
                    log.w { "Failed to parse Trakt auth payload: ${it.message}" }
                    TraktAuthState()
                }
        }
        publish(statusMessage = null, errorMessage = null)
    }

    private fun clearDeviceCode() {
        authState = authState.copy(
            deviceCode = null,
            userCode = null,
            verificationUrl = null,
            intervalSeconds = null,
            deviceCodeExpiresAtMillis = null,
            pendingAuthorizationStartedAtMillis = null,
            pendingAuthorizationState = null,
        )
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        statusMessage: String? = _uiState.value.statusMessage,
        errorMessage: String? = _uiState.value.errorMessage,
    ) {
        val tokenExpiresAtMillis = authState.createdAt
            ?.let { createdAtSeconds ->
                authState.expiresIn?.let { expiresInSeconds ->
                    (createdAtSeconds + expiresInSeconds) * 1_000L
                }
            }

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            !authState.deviceCode.isNullOrBlank() || !authState.pendingAuthorizationState.isNullOrBlank() ->
                TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = TraktAuthUiState(
            mode = mode,
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = authState.username,
            effectiveClientId = getEffectiveClientId(currentProfileId),
            customClientId = authState.customClientId,
            userCode = authState.userCode,
            verificationUrl = authState.verificationUrl ?: DEFAULT_VERIFICATION_URL,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            pendingAuthorizationStartedAtMillis = authState.pendingAuthorizationStartedAtMillis,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )
    }

    private fun persist(profileId: Int = currentProfileId) {
        TraktAuthStorage.savePayload(profileId, json.encodeToString(authState))
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAtSeconds = createdAt + expiresIn
        val nowSeconds = TraktPlatformClock.nowEpochMs() / 1_000L
        return nowSeconds >= (expiresAtSeconds - 60)
    }

    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }
}

internal enum class TraktTokenRefreshResponseAction {
    ACCEPT,
    INVALIDATE,
    TRANSIENT_FAILURE,
}

internal fun traktTokenRefreshResponseAction(status: Int): TraktTokenRefreshResponseAction = when {
    status == 400 -> TraktTokenRefreshResponseAction.INVALIDATE
    status in 200..299 -> TraktTokenRefreshResponseAction.ACCEPT
    else -> TraktTokenRefreshResponseAction.TRANSIENT_FAILURE
}

@Serializable
private data class TraktDeviceCodeRequest(
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktDeviceTokenRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TraktRevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class TraktUserSettingsResponse(
    val user: TraktUserDto? = null,
)

@Serializable
private data class TraktUserDto(
    val username: String? = null,
    val ids: TraktUserIdsDto? = null,
)

@Serializable
private data class TraktUserIdsDto(
    val slug: String? = null,
)
