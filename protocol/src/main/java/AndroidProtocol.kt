
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

var nextId = System.currentTimeMillis()

@Serializable
sealed class DroidMessage(val id: Long = nextId++) {
    fun toJson() = Json.encodeToJsonElement(serializer(), this)
    companion object {
        fun fromJson(string: String) = Json.decodeFromString(serializer(), string)
    }
}

@Serializable
class SetVolume(val `val`: Double) : DroidMessage()

@Serializable
class ScreenStatus(val `val`: Boolean) : DroidMessage()

@Serializable
class GetScreenStatus : DroidMessage()

@Serializable
class GetVolume : DroidMessage()

@Serializable
class StartVLC(val url: String) : DroidMessage()

@Serializable
class Init(
    val manufactorer: String,
    val model: String,
    val pid: Long = 0,
    val time: Long = 0
) : DroidMessage()
