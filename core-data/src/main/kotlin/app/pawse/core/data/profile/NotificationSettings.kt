package app.pawse.core.data.profile

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The six kinds of notification, each with its default. The weekly note and the
 * overnight heart rate alert are on; everything else waits to be turned on.
 * Android's own download notice has no switch.
 */
enum class NotifyKind(val default: Boolean) { WEEKLY(true), ILLNESS(true), SUMMARY(false), WIND_DOWN(false), NO_NIGHT(false) }

/**
 * Notification switches, in plain shared preferences so the code that posts them
 * can read them synchronously from a worker or a widget update.
 */
@Singleton
class NotificationSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Map<NotifyKind, Boolean>> = _state.asStateFlow()

    fun isOn(kind: NotifyKind): Boolean = prefs.getBoolean(key(kind), kind.default)

    fun set(kind: NotifyKind, on: Boolean) {
        prefs.edit { putBoolean(key(kind), on) }
        _state.value = read()
    }

    private fun read(): Map<NotifyKind, Boolean> = NotifyKind.entries.associateWith(::isOn)

    private fun key(kind: NotifyKind) = "on_${kind.name.lowercase()}"

    companion object {
        /** Shared with the weekly note's own bookkeeping. */
        const val PREFS = "pawse_notify"
    }
}
