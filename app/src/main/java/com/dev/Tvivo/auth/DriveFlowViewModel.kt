package com.dev.Tvivo.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.source.AuthorizationStep
import com.dev.Tvivo.source.drive.BootstrapStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DriveAccount(
    val session: SourceSession.Drive,
    val displayName: String?,
    val email: String?,
    val folderId: String?,
    val lastSyncAt: Long?
)

sealed interface DriveFlowState {
    data object Idle : DriveFlowState
    data object RequestingCode : DriveFlowState
    data class DeviceCode(val url: String, val code: String, val expiresAt: Long) : DriveFlowState
    data class Progress(val step: BootstrapStep) : DriveFlowState
    data class ChooseFolder(val ids: List<String>) : DriveFlowState
    data class Failed(val message: String, val retryAuthorization: Boolean = false) : DriveFlowState
    data class Ready(val account: DriveAccount, val seeded: Boolean) : DriveFlowState
}

interface DriveFlowGateway {
    suspend fun begin(isTelevision: Boolean): AuthorizationStep
    suspend fun continueAuthorization(): AuthorizationStep
    suspend fun prepare(onStep: (BootstrapStep) -> Unit): DrivePreparation
    suspend fun chooseFolder(id: String)
    suspend fun cancel()
}

sealed interface DrivePreparation {
    data class Ready(val account: DriveAccount, val seeded: Boolean) : DrivePreparation
    data class Choose(val ids: List<String>) : DrivePreparation
}

class DriveFlowViewModel(private val gateway: DriveFlowGateway) : ViewModel() {
    private val _state = MutableStateFlow<DriveFlowState>(DriveFlowState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun start(isTelevision: Boolean) {
        job?.cancel()
        _state.value = DriveFlowState.RequestingCode
        job = viewModelScope.launch {
            try { handle(gateway.begin(isTelevision)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { fail(failure, true) }
        }
    }

    fun refreshCode() = start(true)

    private suspend fun handle(step: AuthorizationStep) {
        when (step) {
            AuthorizationStep.Ready -> prepare()
            is AuthorizationStep.DeviceCode -> {
                val code = DriveFlowState.DeviceCode(step.verificationUrl, step.userCode, step.expiresAtMillis)
                _state.value = code
                while (true) {
                    delay(step.pollIntervalSeconds.coerceAtLeast(1) * 1000L)
                    when (val next = gateway.continueAuthorization()) {
                        AuthorizationStep.Pending -> continue
                        else -> { handle(next); return }
                    }
                }
            }
            is AuthorizationStep.PhoneConsent -> handle(gateway.continueAuthorization())
            AuthorizationStep.Pending -> {
                delay(1000)
                handle(gateway.continueAuthorization())
            }
            AuthorizationStep.Expired -> _state.value = DriveFlowState.Failed("Code expired. Refresh code to try again.", true)
            AuthorizationStep.Denied -> _state.value = DriveFlowState.Failed("Google Drive approval was declined. Try again.", true)
            AuthorizationStep.ReauthRequired -> _state.value = DriveFlowState.Failed("Google Drive access expired. Sign in again.", true)
        }
    }

    private suspend fun prepare() {
        _state.value = DriveFlowState.Progress(BootstrapStep.FINDING_LIBRARY)
        val result = gateway.prepare { step -> _state.value = DriveFlowState.Progress(step) }
        _state.value = when (result) {
            is DrivePreparation.Ready -> DriveFlowState.Ready(result.account, result.seeded)
            is DrivePreparation.Choose -> DriveFlowState.ChooseFolder(result.ids)
        }
    }

    fun retry() {
        job?.cancel()
        job = viewModelScope.launch {
            try { prepare() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { fail(failure) }
        }
    }

    fun chooseFolder(id: String) {
        job?.cancel()
        job = viewModelScope.launch {
            try { gateway.chooseFolder(id); prepare() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { fail(failure) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = viewModelScope.launch { gateway.cancel(); _state.value = DriveFlowState.Idle }
    }

    private fun fail(failure: Throwable, retryAuthorization: Boolean = false) {
        _state.value = DriveFlowState.Failed(DriveFailureCopy.message(failure), retryAuthorization)
    }
}

object DriveFailureCopy {
    fun message(failure: Throwable): String = when {
        failure is com.dev.Tvivo.source.drive.DriveFailure.QuotaExceeded ->
            "Drive is full. No library files were changed. Free some space, then retry."
        failure is com.dev.Tvivo.source.drive.DriveFailure.Offline ->
            "You're offline. Your saved library is still available. Retry when connected."
        failure.message?.contains("index", ignoreCase = true) == true ||
            failure.message?.contains("marker", ignoreCase = true) == true ->
            "A library file needs repair. Fix the JSON file in Google Drive, then retry. Saved titles were kept."
        failure is IllegalStateException && failure.message?.contains("configured") == true ->
            "Google Drive is unavailable in this build. Configure the Drive client, then try again."
        else -> "Couldn't open Google Drive. Check the connection and retry."
    }
}
