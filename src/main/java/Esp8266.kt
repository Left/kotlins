import com.google.gson.annotations.SerializedName

data class Esp8266Settings(
        @SerializedName("device.name") val deviceName: String,
        @SerializedName("device.name.russian") val deviceNameRussian: String,
        @SerializedName("wifi.name") val wifiName: String,
        @SerializedName("debug.to.serial") val debugToSerial: Boolean,
        @SerializedName("websocket.server") val websocketServer: String,
        @SerializedName("websocket.port") val websocketPort: String,
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
        @SerializedName("relay.names") val relayNames: String,
        val hasGPIO1Relay: Boolean,
        val hasHC_SR: Boolean, // Ultrasonic distance meter
        val hasPWMOnD0: Boolean
)