package com.dev.Tvivo.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.data.AppError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val serverFieldError: String? = null,
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    val errorMessageOverride: String? = null,
    /** Set after a rejected login so the screen can focus and select-all the password. */
    val refocusPassword: Boolean = false,
    val authenticated: AuthenticatedAccount? = null
) {
    val canSubmit: Boolean
        get() = !isSubmitting && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/**
 * A single `(Application)` constructor, deliberately: `AndroidViewModelFactory` resolves
 * it reflectively, and Kotlin default arguments do not generate one.
 */
class LoginViewModel internal constructor(
    application: Application,
    private val authenticate: suspend (Credentials) -> Result<AuthenticatedAccount>,
    private val credentialsStore: LoginCredentialStore,
    private val sourceStore: LoginSourceStore
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        AuthRepository()::authenticate,
        CredentialsStore(application).asLoginCredentialStore(),
        LoginSourceStore { value ->
            check(SourceStore.open(application).stageSwitch(SavedSource.xtream(value)) { true })
        }
    )

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onServerChanged(value: String) =
        _state.update { it.copy(server = value, serverFieldError = null, error = null, errorMessageOverride = null) }

    fun onUsernameChanged(value: String) =
        _state.update { it.copy(username = value, error = null, errorMessageOverride = null) }

    fun onPasswordChanged(value: String) =
        _state.update { it.copy(password = value, error = null, errorMessageOverride = null, refocusPassword = false) }

    fun onTogglePasswordVisibility() =
        _state.update { it.copy(showPassword = !it.showPassword) }

    /**
     * Explicit, and never automatic. A rejected login keeps every field — re-typing a
     * ~44-character credential set on a D-pad is 200+ directional presses — so the only
     * way the form empties is the user asking for it.
     */
    fun onClear() = _state.update {
        LoginUiState(showPassword = it.showPassword)
    }

    /**
     * Validate incrementally: a typo in the server field surfaces when the field is
     * committed, before a 44-character credential set is typed on a D-pad.
     */
    fun onServerCommitted() = _state.update {
        if (it.server.isBlank()) it
        else it.copy(
            serverFieldError = if (ServerAddress.parse(it.server) == null) {
                "That doesn't look like a server address"
            } else null
        )
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        val parsed = ServerAddress.parse(current.server)
        if (parsed == null) {
            _state.update { it.copy(serverFieldError = "That doesn't look like a server address") }
            return
        }

        _state.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            val credentials = Credentials(
                host = parsed.host,
                port = parsed.port,
                username = current.username.trim(),
                password = current.password,
                useHttps = parsed.useHttps
            )

            authenticate(credentials)
                .onSuccess { account ->
                    val previous = runCatching { credentialsStore.load() }
                    val commit = previous.fold(
                        onSuccess = { old ->
                            runCatching {
                                credentialsStore.save(account.credentials)
                                sourceStore.stage(account.credentials)
                            }.onFailure {
                                // Best-effort rollback prevents a failed source commit from
                                // replacing the legacy credentials used by older startup paths.
                                runCatching {
                                    if (old == null) credentialsStore.wipe()
                                    else credentialsStore.save(old)
                                }
                            }
                        },
                        onFailure = { Result.failure(it) }
                    )
                    if (commit.isSuccess) {
                        _state.update { it.copy(isSubmitting = false, authenticated = account) }
                    } else {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                error = AppError.Unreachable,
                                errorMessageOverride = "Could not save this account. Please try again."
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    val error = (throwable as? AppErrorException)?.error ?: AppError.Unreachable
                    // The form is never cleared on failure: retyping 44 characters on a
                    // remote is the most expensive thing this screen can ask for.
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            error = error,
                            errorMessageOverride = null,
                            refocusPassword = error is AppError.AuthFailed
                        )
                    }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null, errorMessageOverride = null) }
}

internal interface LoginCredentialStore {
    suspend fun load(): Credentials?
    suspend fun save(value: Credentials)
    suspend fun wipe()
}

internal fun interface LoginSourceStore {
    suspend fun stage(value: Credentials)
}

private fun CredentialsStore.asLoginCredentialStore() = object : LoginCredentialStore {
    override suspend fun load() = this@asLoginCredentialStore.load()
    override suspend fun save(value: Credentials) = this@asLoginCredentialStore.save(value)
    override suspend fun wipe() = this@asLoginCredentialStore.wipe()
}
