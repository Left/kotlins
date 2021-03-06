
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramPacket
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.ServerWebSocket
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.random.Random

inline class ClientId(val v: Int) {
    companion object {
        private var cnt = Random(System.currentTimeMillis().toInt()).nextInt().ushr(1)
        fun next() : ClientId = ClientId(cnt++)
    }
}

@ExperimentalCoroutinesApi
open class HttpVerticle(val port: Int) : CoroutineVerticle() {
    val client: RedisClient by lazy {
        RedisClient.create(RedisURI("127.0.0.1", 6379, Duration.ofMinutes(10)))
    }

    val redisAsync by lazy {
        client.connect(ByteArrayCodec()).async()
    }

    val PULLUPS = "pullups".toByteArray(Charsets.UTF_8)

    class ClientConnection(val id: ClientId) {
        val openedAt = Instant.now()
        val actions = mutableMapOf<String, suspend () -> Unit>()
        var sender: (txt: String) -> Unit = {}
    }

    class Controller(val ip: String, val settings: Esp8266Settings, private var sender: (bytes: ByteArray) -> Unit) {
        var lastPacketAt = Instant.now()
        var cnt: Int = 1
        val connectedAt: Instant = Instant.now()
        val readableName = settings.deviceNameRussian
        val shortName = settings.deviceName
        var timeseq: Int = Int.MAX_VALUE

        fun send(msg: Protocol.MsgBack.Builder) {
            sender.invoke(
                msg.setId(cnt ++)
                    .build()
                    .toByteArray())
        }
    }

    val controllers = MutableStateFlow<Map<String, Controller>>(emptyMap())

    var globalIdx = 0
    val buttons = mutableMapOf<String, suspend () -> Unit>()

    fun btn(id: String, name:String, action: suspend () -> Unit): String {
        buttons[id] = action
        return "<button onclick=\"fetch('/btn?id=${URLEncoder.encode(id, "utf-8")}')\">${name}</button>"
    }

    suspend fun render(clientId: ClientId, fl: Flow<String>): String {
        val idx = globalIdx++
        val ret = withTimeoutOrNull(1) {
            fl.take(1).single()
        }

        return "<span id='v${idx}'>${ret ?: ""}</span>"
    }

    val DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))
    val TIME_FORMATTER = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))

    suspend fun toHtml(rc: RoutingContext, body: suspend (clientId: ClientId) -> String) {
        val clientId = ClientId.next()

        val res = """
                |<html>
                |   <head>
                |       <title>Home server</title>
                |       <script>const _htmlClientId = ${clientId.v}
                |       async function longPoll(url, processor) {
                |           function sleep(ms) {
                |             return new Promise(resolve => setTimeout(resolve, ms));
                |           }
                |       
                |           for (;;) {
                |               try {
                |                   const v = await fetch(url, {
                |                       method: 'GET', // *GET, POST, PUT, DELETE, etc.
                |                       mode: 'cors', // no-cors, *cors, same-origin
                |                       cache: 'no-cache', // *default, no-cache, reload, force-cache, only-if-cached
                |                       credentials: 'same-origin', // include, *same-origin, omit
                |                       headers: {
                |                           'Content-Type': 'application/json'
                |                       },
                |                       redirect: 'follow', // manual, *follow, error
                |                       referrerPolicy: 'no-referrer' // no-referrer, *client
                |                       // body: JSON.stringify(data) // body data type must match "Content-Type" header
                |                   });
                |                   if (v.status == 200) {
                |                       processor(await v.text());
                |                   } else if (v.status == 204) {
                |                       return; // The stream has finished
                |                   }
                |               } catch (e) {
                |                   await sleep(200)
                |               }
                |           }
                |       }
                |       </script>
                |
                |   </head>
                |   <body>
                |   ${body.invoke(clientId)}
                |   </body>
                |</html>
            """.trimMargin()

        rc.response().headers().add("Content-type", "text/html;charset=utf-8")
        rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
    }

    private val longpollFlows = mutableMapOf<Long, BroadcastChannel<String>>()
    private var longpollFlowId: Long = System.currentTimeMillis()
    private var sc = CoroutineScope(newSingleThreadContext("scope"))

    private fun longPoll(fl: Flow<String>, jsCode: String): String {
        val id = longpollFlowId++
        longpollFlows[id] = fl.broadcastIn(sc)

        return """
            <script type='text/javascript'>longPoll("/poll?id=${id}", function (d) { 
                ${jsCode}
            })</script> 
        """.trimIndent()
    }

    var spanId = 0
    internal suspend fun dynamic(fl: Flow<String>): String {
        spanId++
        return longPoll(fl, "document.getElementById('sp$spanId').innerHTML = d;") + """<span id='sp$spanId'>${fl.first()}</span>"""
    }

    val tablets = MutableStateFlow(emptyList<Tablet>())
    val pullups = MutableStateFlow(emptySequence<Serie>())

    internal val udpPort = 89
    internal val udpSocket: DatagramSocket by lazy {
        vertx.createDatagramSocket(DatagramSocketOptions())
    }

    var ms = 0

    // data class Time(val time: Int, val count: Int, val pullupFlag: Boolean)

    // val dests = mutableListOf<Time>()
    val pp = mutableListOf<Boolean>()

    override suspend fun start() {
        launch {
            pullups.value = retreivePullupsData()
        }

        // Build Vert.x Web router
        val router = Router.router(vertx)

        router.get("/").coroutineHandler { rc ->
            rc.redirect("/pa")
        }

        router.get("/tt").coroutineHandler { rc ->
            // val html = PlotHtmlExport.buildHtmlFromRawSpecs(spec, iFrame = true)

        }

        router.get("/btn").coroutineHandler { rc ->
            val id = rc.request().getParam("id")
            buttons[id]?.invoke()
            rc.okText("OK")
        }

        router.get("/poll").coroutineHandler { rc ->
            val id = rc.request().getParam("id")
            val fl = longpollFlows[id?.toLong() ?: Long.MIN_VALUE]

            val first = fl?.asFlow()?.firstOrNull()
            if (first != null) {
                rc.response().headers().add("Content-type", "text/plain;charset=UTF-8")
                rc.response().end(Buffer.buffer(first.toByteArray(Charsets.UTF_8)))
            } else {
                rc.response().statusCode = 204 // Means flow end
                rc.response().end()
            }
        }

        val tabletsTable = Table<Tablet>(cellpadding = "3").apply {
            column("Manufactorer", {
                it.manufactorer
            })
            column("Model", {
                it.model
            })
            column("Pid", {
                it.pid.toString()
            })
            column("Ts", {
                it.ts.toString()
            })
            column("Screen", {
                "<input type='checkbox' value='1'></checkbox>"
            })
        }

        router.get("/tablets").coroutineHandler { rc ->
            // Tablets table
            toHtml(rc) { clientId ->
                dynamic(tablets.map {
                    tabletsTable.render(it.sortedBy { it.manufactorer }.asIterable())
                })
            }
        }

        pullups(router)

        devices(router)

        val wsHandler = object: Handler<ServerWebSocket> {
            override fun handle(ws: ServerWebSocket?) {
                if (ws == null) return

                println("Established websocket ${ws.binaryHandlerID()}")

                var timeout: Deferred<Unit>? = null

                // Go on
                ws.accept()
                ws.handler { buffer ->
                    timeout?.cancel()
                    timeout = async {
                        delay(15_000L)
                        println("Timeout, pinging")
                        ws.writeFinalTextFrame(GetScreenStatus().toJson().toString())
                        delay(10_000L)
                        println("Timeout")
                        ws.close()
                    }

                    if (buffer.length() == 0) {
                        return@handler // ping?
                    }

                    try {
                        val string = String(buffer.bytes, Charsets.UTF_8)
                        println(string)
                        val msg = DroidMessage.fromJson(string)
                        if (msg is Init) {
                            // ws.writeFinalTextFrame(SetVolume(50.0).toJson().toString())
                            ws.writeFinalTextFrame(GetScreenStatus().toJson().toString())
                            ws.writeFinalTextFrame(ScreenStatus(true).toJson().toString())

                            runBlocking {
                                tablets.value =
                                    tablets.first() + listOf(
                                        Tablet(ws, msg.manufactorer, msg.model, msg.pid, msg.time)
                                    )
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }

                fun removeMe() = runBlocking {
                    val filtered = tablets.first().filterNot { it.ws == ws }
                    tablets.value = filtered
                    println("Removed websocket ${ws.binaryHandlerID()}")
                }

                ws.exceptionHandler { removeMe() }
                ws.closeHandler { removeMe() }
                ws.pongHandler {
                    println("pong")
                }
            }
        }

        udpSocket.listen(udpPort, "0.0.0.0") { asyncResult ->
            if (asyncResult.succeeded()) {
                println("UDP endpoint is 127.0.0.1:${udpPort}")
                udpSocket.handler { packet ->
                    sc.async {
                        try {
                            val ip = packet.sender().host()!!
                            // println("Packet came from ${ip}")

                            val msg = Protocol.Msg.parseFrom(packet.data().bytes) ?: error("Can't parse UDP message")

                            processMessage(msg, ip, packet)

                            Unit
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                    // println("${Instant.now()} PACKET: ${msg} ${controllers.value[ip]?.lastPacketAt}")
                }
            } else {
                println("Listen failed")
            }
        }

        launch {
            while (true) {
                delay(1000)
                // Ping all controllers each 2 seconds
                for (c in controllers.value) {
                    c.value.send(Protocol.MsgBack.newBuilder())
                }
            }
        }

        vertx.createHttpServer(HttpServerOptions().also {
            it.idleTimeout = 4000
        })
            .requestHandler(router)
            .webSocketHandler(wsHandler)
            .listenAwait(port)
    }

    private suspend fun processMessage(
        msg: Protocol.Msg,
        ip: String,
        packet: DatagramPacket
    ) {
        if (msg.hasDebugLogMessage()) {
            println("log:>" + msg.debugLogMessage)
        }

        if (msg.hello != null && msg.hello.settings.isNotBlank() &&
            (!controllers.value.contains(ip) ||
                    controllers.value[ip]!!.timeseq > msg.timeseq)
        ) {
            val sndr = udpSocket.sender(packet.sender().port(), packet.sender().host())

            val newController = Controller(
                ip, Json.decodeFromString(msg.hello.settings)
            ) {
                sndr.write(Buffer.buffer(it))
            }
            println("""Controller ${ip} "${newController.readableName}" """)
            controllers.value = (controllers.value + (ip to newController))

            newController.send(
                Protocol.MsgBack.newBuilder()
                    .setUnixtime((System.currentTimeMillis() / 1000L).toInt())
            )
        } else {
            if (!controllers.value.contains(ip)) {
                // Packet comes, but we don't know this controller.
                // Let it introduce self first
                udpSocket.send(
                    Buffer.buffer(
                        Protocol.MsgBack.newBuilder()
                            .setId(42)
                            .setIntroduceYourself(true)
                            .build()
                            .toByteArray()
                    ), packet.sender().port(), packet.sender().host()
                ) {
                    // Do we need to do something here?
                }
            } else {
                val controller = controllers.value[ip]
                if (msg.hasHcsrOn() &&
                    !msg.hcsrOn &&
                    controller?.shortName == "PullupCounter"
                ) {
                    pullupPerformed()
                }
                if (msg.destiniesList.isNotEmpty()) {
                    if (msg.destiniesList.any { it < 2500 || it > 10000 } ) {
                        //

                    }

                    msg.destiniesList.chunked(2).forEach {
                        val pu = it[1] < 2500 || it[1] > 10000
                        pp.add(pu)
                    }

                    if (pp.count { it } > 4) {
                        if (pp.takeLast(8).all { !it }) {
                            pullupPerformed()
                            pp.clear()
                        }
                    } else if (pp.takeLast(300).all { !it }) {
                        pp.clear()
                    }
                }
                if (msg.hasPotentiometer()) {
                    println("POTENTIOMETER: ${msg.potentiometer}")
                }
                if (msg.hasParsedRemote()) {
                    val pr = msg.parsedRemote
                    println(pr.remote + " -> " + pr.key)
                }
                // if (msg.parsedRemote)

                controller!!.timeseq = msg.timeseq
                /*
                async {
                    val now = Instant.now()
                    controller!!.lastPacketAt = now
                    delay(6000)
                    if (controller!!.lastPacketAt == now) {
                        println("!!!!!")
                        // For 6s we didn't receive anything.
                        controllers.value -= ip
                        return@async
                    }
                }
                 */
            }
        }
    }

    private fun devices(router: Router) {
        val devicesTable = Table<Map.Entry<String, Controller>>(cellpadding = "3").apply {
            column("Id", {
                "${it.key}<br/>${it.value.shortName}<br/>${it.value.readableName}"
            }) {}
            column("Connected at", {
                "{${it.value.connectedAt}}"
            }) {}
            column("Settings", {
                "{${it.value.settings}}"
            }) {}
            column("Actions", {
                btn("restart" + it.value.ip, "Restart") {
                    it.value.send(Protocol.MsgBack.newBuilder().setReboot(true))
                    delay(100)
                    controllers.value = controllers.value - it.key
                }
            }) {}
        }

        router.get("/devices").coroutineHandler { rc ->
            toHtml(rc) { clientId -> dynamic(
                controllers.map {
                    devicesTable.render(it.asIterable())
                })
            }
        }
    }

    internal suspend fun HttpVerticle.pullupPerformed(): String {
        redisAsync.lpush(PULLUPS, Instant.now().toEpochMilli().toByteArray()).await()

        val ser = getSeries(100)

        val res = (ser.firstOrNull()?.refs?.size ?: 0)

        pullups.value = retreivePullupsData()

        playSoundTemp(if (res % 10 == 0) 604 else (if (res < 10) 549 else 560))

        return res.toString()
    }

    internal suspend fun retreivePullupsData(): Sequence<Serie> =
        getSeries(redisAsync.llen(PULLUPS).await())

    companion object {
        private val systemZoneOffset = ZoneOffset.systemDefault()
    }

    data class Serie(val end: Instant, val refs: List<Long>) {
        val time by lazy {
            LocalDateTime.ofInstant(end, systemZoneOffset)
        }
    }

    private suspend fun getSeries(max: Long = 1000): Sequence<Serie> {
        val tp = redisAsync.lrange(PULLUPS, 0, max).await()
        if (tp.isEmpty()) {
            return emptySequence()
        }
        val times = tp.asSequence().map { it.toLong() }
        return sequence {
            var prev = times.first()
            var list = mutableListOf(prev)
            times.drop(1).forEach {
                // 30 seconds means starting new series
                if (prev - it > 30_000) {
                    yield(Serie(Instant.ofEpochMilli(prev), list))
                    list = mutableListOf(it)
                } else {
                    list.add(it)
                }
                prev = it
            }
            yield(Serie(Instant.ofEpochMilli(prev), list))
        }
    }

    fun Route.coroutineHandler(fn: suspend (RoutingContext) -> Unit) {
        handler { ctx ->
            launch(Dispatchers.IO) {
                try {
                    fn(ctx)
                } catch (e: Exception) {
                    ctx.response().statusCode = 500
                    ctx.response().putHeader("Content-type", "text/plain")
                    ctx.response().end(e.message)
                }
            }
        }
    }
}