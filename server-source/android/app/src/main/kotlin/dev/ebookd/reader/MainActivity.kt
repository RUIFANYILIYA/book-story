package dev.ebookd.reader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.ebookd.core.design.EbookdTheme
import dev.ebookd.reader.nav.EbookdNavHost
import dev.ebookd.reader.nav.RootViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var volumeKeys: VolumeKeyRelay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EbookdTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val root: RootViewModel = hiltViewModel()
                    val start by root.start.collectAsStateWithLifecycle()
                    when (val state = start) {
                        // One frame of nothing, rather than a flash of the address screen that the
                        // reader would have to watch being dismissed.
                        RootViewModel.Start.Loading -> Unit
                        is RootViewModel.Start.Ready -> EbookdNavHost(startRoute = state.route)
                    }
                }
            }
        }
    }

    /**
     * Volume keys for the open book, if it wants them.
     *
     * Both `onKeyDown` and `onKeyUp` are overridden and both are symmetric: a handler that swallowed
     * only the down event would leave the up event to the system, which then decides the press was a
     * volume press after all and shows the panel — the page turned *and* the volume UI appeared.
     * Both events are offered to the reader but only the down one acts; auto-repeat arrives here as
     * further down events, so a held key turns pages at the platform's repeat rate, which is the
     * "长按自动翻" the plan asks for without a timer of our own.
     */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = consumeVolumeKey(keyCode, event.action) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = consumeVolumeKey(keyCode, event.action) || super.onKeyUp(keyCode, event)

    private fun consumeVolumeKey(
        keyCode: Int,
        action: Int,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return volumeKeys.handler?.invoke(keyCode, action) == true
    }
}
