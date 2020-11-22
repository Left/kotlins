import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Esp8266Settings(
        @SerialName("device.name") val deviceName: String,
        @SerialName("device.name.russian") val deviceNameRussian: String,
        @SerialName("wifi.name") val wifiName: String,
        @SerialName("debug.to.serial") val debugToSerial: Boolean,
        @SerialName("websocket.server") val websocketServer: String,
        @SerialName("websocket.port") val websocketPort: String,
        val invertRelay: Boolean,
        val hasScreen: Boolean,
        val hasScreen180Rotated: Boolean,
        val hasHX711: Boolean,
        val hasIrReceiver: Boolean,
        val hasDS18B20: Boolean,
        val hasDFPlayer: Boolean,
        val hasBME280: Boolean,
        val hasLedStripe: Boolean,
        val hasBluePill: Boolean,
        val hasButtonD7: Boolean,
        val hasButtonD2: Boolean,
        val hasButtonD5: Boolean,
        val brightness: String,
        val hasEncoders: Boolean,
        val hasMsp430WithEncoders: Boolean,
        val hasPotenciometer: Boolean,
        val hasSSR: Boolean,
        val hasATXPowerSupply: Boolean,
        @SerialName("relay.names")
        val relayNames: String,
        val hasGPIO1Relay: Boolean,
        val hasHC_SR: Boolean, // Ultrasonic distance meter
        val hasPWMOnD0: Boolean
)