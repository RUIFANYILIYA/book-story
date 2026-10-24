package dev.ebookd.reader.ui.serversetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.net.ServerProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The address screen's logic.
 *
 * The interesting work here is not the probe but the *translation*: every way this can fail has a
 * different fix, and the person reading the message is holding a phone, standing somewhere in their
 * house. "Connection refused" is not an answer; "the port is not answering — check the server is
 * running and the firewall allows 8787" is.
 */
@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val probe: ServerProbe,
    private val settings: SettingsStore,
) : ViewModel() {

    data class State(
        val address: String = "",
        val busy: Boolean = false,
        val isError: Boolean = false,
        val message: String? = null,
        val configured: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.first()?.let { existing ->
                _state.update { it.copy(address = existing) }
            }
        }
    }

    fun onAddressChange(value: String) {
        _state.update { it.copy(address = value, message = null, isError = false) }
    }

    fun connect() {
        val typed = _state.value.address
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, isError = false) }
            when (val outcome = probe.probe(typed)) {
                is ServerProbe.Outcome.Success -> {
                    // The *normalised* address is what gets stored: the user's `.../api` suffix and
                    // trailing slashes must not end up in every future request path.
                    settings.setBaseUrl(outcome.baseUrl)
                    _state.update { it.copy(busy = false, configured = true, message = null) }
                }
                else -> _state.update {
                    it.copy(busy = false, isError = true, message = describe(outcome))
                }
            }
        }
    }

    private fun describe(outcome: ServerProbe.Outcome): String = when (outcome) {
        is ServerProbe.Outcome.InvalidAddress -> outcome.reason
        ServerProbe.Outcome.DnsFailure -> "找不到这个主机名。检查地址拼写，并确认手机和服务器在同一个网络里。"
        ServerProbe.Outcome.ConnectionRefused -> "主机名能解析，但这个端口没有响应。服务端可能没启动，或被防火墙挡住了。"
        ServerProbe.Outcome.Timeout -> "连接超时。地址可能不对，或者网络不通。"
        ServerProbe.Outcome.TlsFailure -> "TLS 握手失败。自签证书需要先在系统里信任，或改用 http 地址。"
        ServerProbe.Outcome.NotEbookd -> "有响应，但不是 ebookd 服务。检查端口是否指向了别的东西。"
        is ServerProbe.Outcome.ProtocolMismatch ->
            "服务端协议版本是 ${outcome.serverProtocol}，与本应用不兼容。请升级服务端或应用。"
        is ServerProbe.Outcome.Success -> ""
    }
}
