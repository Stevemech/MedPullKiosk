package com.medpull.kiosk.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages user session timeout and activity tracking
 * Implements 15-minute inactivity timeout with warning
 */
@Singleton
class SessionManager @Inject constructor() {

    private var lastActivityTime: Long = System.currentTimeMillis()
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Active)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _remainingTime = MutableStateFlow(Constants.Session.TIMEOUT_MS)
    val remainingTime: StateFlow<Long> = _remainingTime.asStateFlow()

    /**
     * Start session monitoring
     */
    fun startSession() {
        lastActivityTime = System.currentTimeMillis()
        _sessionState.value = SessionState.Active
        startTimeoutMonitoring()
    }

    /**
     * Stop session monitoring
     */
    fun stopSession() {
        timeoutJob?.cancel()
        timeoutJob = null
        _sessionState.value = SessionState.Inactive
    }

    /**
     * Record user activity (resets timeout)
     */
    fun recordActivity() {
        lastActivityTime = System.currentTimeMillis()
        if (_sessionState.value == SessionState.Warning) {
            _sessionState.value = SessionState.Active
        }
    }

    /**
     * Force session expiration
     */
    fun expireSession() {
        _sessionState.value = SessionState.Expired
        stopSession()
    }

    /**
     * Check if session is still valid
     */
    fun isSessionValid(): Boolean {
        val elapsedTime = System.currentTimeMillis() - lastActivityTime
        return elapsedTime < Constants.Session.TIMEOUT_MS
    }

    /**
     * Get remaining time until timeout
     */
    fun getRemainingTimeMs(): Long {
        val elapsedTime = System.currentTimeMillis() - lastActivityTime
        return (Constants.Session.TIMEOUT_MS - elapsedTime).coerceAtLeast(0)
    }

    /**
     * Start monitoring for timeout
     */
    private fun startTimeoutMonitoring() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            while (true) {
                delay(Constants.Session.ACTIVITY_CHECK_INTERVAL_MS)

                val remaining = getRemainingTimeMs()
                _remainingTime.value = remaining

                when {
                    remaining <= 0 -> {
                        _sessionState.value = SessionState.Expired
                        break
                    }
                    remaining <= (Constants.Session.TIMEOUT_MS - Constants.Session.WARNING_THRESHOLD_MS) -> {
                        if (_sessionState.value != SessionState.Warning) {
                            _sessionState.value = SessionState.Warning
                        }
                    }
                    else -> {
                        if (_sessionState.value == SessionState.Warning) {
                            _sessionState.value = SessionState.Active
                        }
                    }
                }
            }
        }
    }

    /**
     * Format remaining time as MM:SS
     */
    fun formatRemainingTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

/**
 * Session state enum
 */
sealed class SessionState {
    object Active : SessionState()
    object Warning : SessionState()
    object Expired : SessionState()
    object Inactive : SessionState()
}
