package app.halo.light

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recent connection events, shown in Settings → Diagnostics so problems can be shared. */
object Diagnostics {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        android.util.Log.d("Halo", line)
        _lines.value = (_lines.value + line).takeLast(MAX)
    }

    fun clear() { _lines.value = emptyList() }
}
