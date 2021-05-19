import io.vertx.core.http.ServerWebSocket
import java.util.*

class Tablet(
    val ws: ServerWebSocket,
    _manufactorer: String,
    _model: String,
    val pid: Long,
    val ts: Long) {
    val manufactorer = _manufactorer.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    val model = _model.removePrefix(manufactorer).trimStart()
}
