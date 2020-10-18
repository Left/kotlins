
import kotlinx.coroutines.flow.MutableStateFlow
import se.vidstige.jadb.JadbConnection
import java.net.InetSocketAddress
import java.util.concurrent.Executors

// import kotlinx.coroutines.CoroutineScope.launch

class Tablet {
    //
    companion object {
        private val availableTablets = MutableStateFlow<List<Tablet>>(emptyList())

        fun listenForDevices() {
            val knowDevs =
                    listOf("192.168.121.166" to 5555)

            knowDevs.forEach { (addr, port) ->
                Executors.newSingleThreadExecutor().execute {
                    var connected = false
                    val jadb = JadbConnection()

                    while (true) {
                        if (!connected) {
                            // Re-connecting to device
                            jadb.connectToTcpDevice(InetSocketAddress.createUnresolved(addr, port))
                        }

                        val devices = jadb.devices
                        connected = devices.any { dev -> dev.serial == addr + ":" + port }

                        println("Dev ")
                        Thread.sleep(100)
                    }
                }
            }

            /*
            (1..100).forEach {
                val ts = measureTimeMillis {
                    val dev = devices[0]
                    val sh = dev.executeShell("dumpsys audio | grep -A 2 -E 'STREAM_MUSIC'")
                    val tba = ByteStreams.toByteArray(sh)
                    println(String(tba, Charsets.UTF_8))
                }

                println(ts)
            }
             */
            //
        }
    }
}
