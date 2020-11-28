
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

// Unwraps a list to a single object
object BoolSerializer :
        JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement {
                return JsonPrimitive((element as? JsonPrimitive)?.content?.run { this  == "true" } ?: false )
        }
}

@Serializable
data class Esp8266Settings(
        @SerialName("device.name") val deviceName: String,
        @SerialName("device.name.russian") val deviceNameRussian: String,
        @SerialName("wifi.name") val wifiName: String,
        @SerialName("debug.to.serial") @Serializable(BoolSerializer::class) val debugToSerial: Boolean? = null,
        @SerialName("websocket.server") val websocketServer: String,
        @SerialName("websocket.port") val websocketPort: String,
        @Serializable(BoolSerializer::class) val invertRelay: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasScreen: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasScreen180Rotated: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasHX711: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasIrReceiver: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasDS18B20: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasDFPlayer: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasBME280: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasLedStripe: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasBluePill: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasButtonD7: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasButtonD2: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasButtonD5: Boolean? = null,
        val brightness: String? = null,
        @Serializable(BoolSerializer::class) val hasEncoders: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasMsp430WithEncoders: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasPotenciometer: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasSSR: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasATXPowerSupply: Boolean? = null,
        @SerialName("relay.names")
        val relayNames: String? = null,
        @Serializable(BoolSerializer::class) val hasGPIO1Relay: Boolean? = null,
        @Serializable(BoolSerializer::class) val hasHC_SR: Boolean? = null, // Ultrasonic distance meter
        @Serializable(BoolSerializer::class) val hasPWMOnD0: Boolean? = null
)