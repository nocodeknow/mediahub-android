package com.nuvio.app.features.trakt

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktAuthRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `HTTP 400 permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(200),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(201),
        )
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(
                TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
                traktTokenRefreshResponseAction(status),
            )
        }
    }

    @Test
    fun `classifyTraktDeviceTokenStatus classifies official Trakt HTTP response codes`() {
        assertEquals(TraktDeviceTokenPollResult.SUCCESS, classifyTraktDeviceTokenStatus(200))
        assertEquals(TraktDeviceTokenPollResult.PENDING, classifyTraktDeviceTokenStatus(400))
        assertEquals(TraktDeviceTokenPollResult.INVALID_CODE, classifyTraktDeviceTokenStatus(404))
        assertEquals(TraktDeviceTokenPollResult.ALREADY_USED, classifyTraktDeviceTokenStatus(409))
        assertEquals(TraktDeviceTokenPollResult.EXPIRED, classifyTraktDeviceTokenStatus(410))
        assertEquals(TraktDeviceTokenPollResult.DENIED, classifyTraktDeviceTokenStatus(418))
        assertEquals(TraktDeviceTokenPollResult.SLOW_DOWN, classifyTraktDeviceTokenStatus(429))
        assertEquals(TraktDeviceTokenPollResult.TRANSIENT_ERROR, classifyTraktDeviceTokenStatus(500))
        assertEquals(TraktDeviceTokenPollResult.TRANSIENT_ERROR, classifyTraktDeviceTokenStatus(502))
        assertEquals(TraktDeviceTokenPollResult.TRANSIENT_ERROR, classifyTraktDeviceTokenStatus(504))
    }

    @Test
    fun `TraktAuthState isAuthenticated checks both accessToken and refreshToken`() {
        assertTrue(
            TraktAuthState(
                accessToken = "valid_access_token",
                refreshToken = "valid_refresh_token",
            ).isAuthenticated,
        )

        assertFalse(
            TraktAuthState(
                accessToken = null,
                refreshToken = "valid_refresh_token",
            ).isAuthenticated,
        )

        assertFalse(
            TraktAuthState(
                accessToken = "valid_access_token",
                refreshToken = null,
            ).isAuthenticated,
        )

        assertFalse(
            TraktAuthState(
                accessToken = "  ",
                refreshToken = "valid_refresh_token",
            ).isAuthenticated,
        )

        assertFalse(
            TraktAuthState(
                accessToken = "valid_access_token",
                refreshToken = "",
            ).isAuthenticated,
        )

        assertFalse(TraktAuthState().isAuthenticated)
    }

    @Test
    fun `TraktDeviceCodeResponse decodes correctly from official JSON payload`() {
        val payload = """
            {
              "device_code": "test_device_code_12345",
              "user_code": "ABCD1234",
              "verification_url": "https://auth.trakt.tv/activate",
              "expires_in": 600,
              "interval": 5
            }
        """.trimIndent()

        val parsed = json.decodeFromString<TraktDeviceCodeResponse>(payload)
        assertEquals("test_device_code_12345", parsed.deviceCode)
        assertEquals("ABCD1234", parsed.userCode)
        assertEquals("https://auth.trakt.tv/activate", parsed.verificationUrl)
        assertEquals(600, parsed.expiresIn)
        assertEquals(5, parsed.interval)
    }

    @Test
    fun `default client ID matches verified public registration`() {
        assertEquals("GG_HeGCytpTZ3yzFxdZ-bnwMgBPzpE0_u8YkQoyM9Lw", TraktAuthRepository.DEFAULT_TRAKT_CLIENT_ID)
        assertEquals("https://auth.trakt.tv/activate", TraktAuthRepository.DEFAULT_VERIFICATION_URL)
    }

    @Test
    fun `device grant rejects unusable codes expiry intervals and activation origins`() {
        val valid = TraktDeviceCodeResponse("device", "USER1234", "https://auth.trakt.tv/activate", 600, 5)
        assertTrue(valid.isValidForSignIn())
        assertFalse(valid.copy(deviceCode = " ").isValidForSignIn())
        assertFalse(valid.copy(userCode = "").isValidForSignIn())
        assertFalse(valid.copy(expiresIn = -1).isValidForSignIn())
        assertFalse(valid.copy(interval = Int.MAX_VALUE).isValidForSignIn())
        assertFalse(valid.copy(verificationUrl = "https://example.com/activate").isValidForSignIn())
        assertFalse(valid.copy(verificationUrl = "http://auth.trakt.tv/activate").isValidForSignIn())
    }
}
