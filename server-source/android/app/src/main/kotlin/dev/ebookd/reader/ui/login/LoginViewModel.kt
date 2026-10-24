package dev.ebookd.reader.ui.login

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ebookd.core.data.CapabilityStore
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.net.AuthRepository
import dev.ebookd.core.net.DeviceDto
import dev.ebookd.reader.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sign-in state for the screen. The device identifies itself so the server can list and revoke this
 * handset separately, and the error text is written here rather than taken from the server, because
 * the server's messages are English and deliberately uniform ("invalid username or password" is one
 * string for both cases so it cannot reveal which usernames exist).
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val capabilities: CapabilityStore,
) : ViewModel() {

    data class State(
        val username: String = "",
        val password: String = "",
        val address: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
        val signedIn: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val lastUser = settings.lastUsername.first()
            val address = settings.baseUrl.first()
            _state.update { it.copy(username = lastUser.orEmpty(), address = address) }
        }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun signIn() {
        val current = _state.value
        if (current.busy || current.username.isBlank() || current.password.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = auth.signIn(
                username = current.username,
                password = current.password,
                device = DeviceDto(label = Build.MODEL, platform = "android", clientVersion = BuildConfig.VERSION_NAME),
            )
            when (result) {
                is AuthRepository.SignInResult.Success -> {
                    settings.setLastUsername(current.username)
                    _state.update { it.copy(busy = false, signedIn = true, password = "", error = null) }
                    // After the screen has moved on, not before: the shelf needs to know what this
                    // session may do, and a slow answer is a menu that fills in a moment later —
                    // while a login that waits on it is a button that spins for no reason.
                    capabilities.refresh()
                }
                AuthRepository.SignInResult.InvalidCredentials ->
                    _state.update { it.copy(busy = false, error = "用户名或口令不正确") }
                AuthRepository.SignInResult.AccountLocked ->
                    _state.update { it.copy(busy = false, error = "失败次数过多，账号被临时锁定，请稍后再试") }
                AuthRepository.SignInResult.AccountDisabled ->
                    _state.update { it.copy(busy = false, error = "这个账号已被停用") }
                AuthRepository.SignInResult.RateLimited ->
                    _state.update { it.copy(busy = false, error = "请求过于频繁，请稍后再试") }
                AuthRepository.SignInResult.UpgradeRequired ->
                    _state.update { it.copy(busy = false, error = "服务端要求升级应用版本") }
                is AuthRepository.SignInResult.Unexpected ->
                    _state.update { it.copy(busy = false, error = "登录失败（HTTP ${result.status}）") }
                AuthRepository.SignInResult.Unreachable ->
                    _state.update { it.copy(busy = false, error = "无法连接服务器。检查网络，以及服务端是否在运行。") }
            }
        }
    }
}
