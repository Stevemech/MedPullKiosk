package com.medpull.kiosk.ui.screens.welcome

import androidx.lifecycle.ViewModel
import com.medpull.kiosk.utils.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel for Welcome screen
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    val sessionManager: SessionManager
) : ViewModel() {

    /**
     * Start new session when user enters app
     */
    fun startSession() {
        sessionManager.startSession()
    }
}
