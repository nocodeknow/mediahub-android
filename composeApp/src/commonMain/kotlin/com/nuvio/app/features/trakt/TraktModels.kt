package com.nuvio.app.features.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val customClientId: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val intervalSeconds: Int? = null,
    val deviceCodeExpiresAtMillis: Long? = null,
    val pendingAuthorizationState: String? = null,
    val pendingAuthorizationStartedAtMillis: Long? = null,
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

enum class TraktConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

data class TraktAuthUiState(
    val mode: TraktConnectionMode = TraktConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val effectiveClientId: String = "",
    val customClientId: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val tokenExpiresAtMillis: Long? = null,
    val pendingAuthorizationStartedAtMillis: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

enum class TraktDeviceTokenPollResult {
    SUCCESS,
    PENDING,
    INVALID_CODE,
    ALREADY_USED,
    EXPIRED,
    DENIED,
    SLOW_DOWN,
    TRANSIENT_ERROR,
}

fun classifyTraktDeviceTokenStatus(statusCode: Int): TraktDeviceTokenPollResult = when (statusCode) {
    200 -> TraktDeviceTokenPollResult.SUCCESS
    400 -> TraktDeviceTokenPollResult.PENDING
    404 -> TraktDeviceTokenPollResult.INVALID_CODE
    409 -> TraktDeviceTokenPollResult.ALREADY_USED
    410 -> TraktDeviceTokenPollResult.EXPIRED
    418 -> TraktDeviceTokenPollResult.DENIED
    429 -> TraktDeviceTokenPollResult.SLOW_DOWN
    else -> TraktDeviceTokenPollResult.TRANSIENT_ERROR
}

@Serializable
data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int,
)

enum class TraktBrandAsset {
    Glyph,
    Wordmark,
}

@Serializable
enum class TraktListType {
    WATCHLIST,
    PERSONAL,
}

@Serializable
enum class TraktListPrivacy(val apiValue: String) {
    PRIVATE("private"),
    LINK("link"),
    FRIENDS("friends"),
    PUBLIC("public");

    companion object {
        fun fromApi(value: String?): TraktListPrivacy =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: PRIVATE
    }
}

@Serializable
data class TraktListTab(
    val key: String,
    val title: String,
    val type: TraktListType,
    val traktListId: Long? = null,
    val slug: String? = null,
    val description: String? = null,
    val privacy: TraktListPrivacy? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
)

data class TraktMembershipSnapshot(
    val listMembership: Map<String, Boolean> = emptyMap(),
)

data class TraktMembershipChanges(
    val desiredMembership: Map<String, Boolean>,
)

internal fun TraktDeviceCodeResponse.isValidForSignIn(): Boolean =
    deviceCode.isNotBlank() && userCode.isNotBlank() &&
        expiresIn in 1..3600 && interval in 1..300 &&
        verificationUrl in setOf("https://auth.trakt.tv/activate", "https://trakt.tv/activate")
