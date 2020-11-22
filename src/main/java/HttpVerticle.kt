
import com.google.protobuf.ByteString
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramSocketOptions
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
import java.time.*
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

    val webSockets = MutableStateFlow<Map<ClientId, ClientConnection>>(mapOf())

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

        launch {
            fl.collect { value ->
                val ws = webSockets.value[clientId]
                ws?.sender?.invoke("document.getElementById('v${idx}').innerHTML = '${value.replace("\n", "\\n").replace("'", "\\'")}'");
            }
        }

        return "<span id='v${idx}'>${ret ?: ""}</span>"
    }

    val DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))
    val TIME_FORMATTER = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))

    suspend fun toHtml(rc: RoutingContext, body: suspend (clientId: ClientId) -> String) {
        val clientId = ClientId.next()

        webSockets.value += (clientId to ClientConnection(clientId))

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
    private fun dynamic(fl: Flow<String>): String {
        spanId++
        return longPoll(fl, "document.getElementById('sp$spanId').innerHTML = d;") + """<span id='sp$spanId'></span>"""
    }

    val adbContext = newSingleThreadContext("ADB")
    val tablets = MutableStateFlow(emptyMap<String, Tablet>())
    val pullups = MutableStateFlow(emptySequence<Serie>())
    
    override suspend fun start() {
        launch {
            pullups.value = retreivePullupsData()
        }

        // Build Vert.x Web router
        val router = Router.router(vertx)

        router.get("/").coroutineHandler { rc ->
            rc.redirect("/pa")
        }

        router.get("/btn").coroutineHandler { rc ->
            val id = rc.request().getParam("id")
            buttons[id]?.invoke()
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

        router.get("/pa").coroutineHandler { rc ->
            // Pullups table
            toHtml(rc) { clientId -> dynamic(pullups.map {
                Table(it.asIterable(),
                        bgColor = { colorFor(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault()).dayOfYear.toString()) },
                        cellpadding = "3").apply {
                    column("Дата", {
                        DATE_FORMATTER.format(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault()))
                    }) {}

                    column("Время", {
                        TIME_FORMATTER.format(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault()))
                    }) {}

                    column("Количество", {
                        it.refs.size.toString()
                    }) {
                        tdAlign = { HAlign.RIGHT }
                    }

                    column("+/-", {
                        if (Instant.now().minus(Duration.ofDays(2)) < it.end) {
                            btn("minus" + it.refs[it.refs.size / 2], "-") {
                                val toDel = it.refs[it.refs.size / 2]
                                redisAsync.lrem(PULLUPS, 0, toDel.toByteArray()).await()
                                pullups.value = retreivePullupsData()
                            } +
                                    btn("plus" + it.refs[0], "+") {
                                        redisAsync.linsert(PULLUPS, true, it.refs[0].toByteArray(), (it.refs[0] - 1).toByteArray()).await()
                                        pullups.value = retreivePullupsData()
                                    }
                        } else {
                            ""
                        }
                    }) {}
                }.render()
            })}
        }

        router.get("/tablets").coroutineHandler { rc ->
            // Pullups table
            toHtml(rc) { clientId -> render(clientId, tablets.map {
                Table(it.asIterable(), cellpadding = "3").apply {
                    column("Serial", {
                        it.key
                    }) {}
                    column("State", {
                        // it.value.state.name
                        ""
                    }) {}
                }.render()
            })}
        }

        router.get("/ws").coroutineHandler { rc ->
            toHtml(rc) { clientId -> render(clientId, webSockets.map {
                Table(it.asIterable(), cellpadding = "3").apply {
                    column("Id", {
                        "{${it.value.id.v}}"
                    }) {}
                    column("Opened", {
                        it.value.openedAt.atZone(ZoneId.systemDefault()).toString()
                    }) {}
                    column("Actions", {
                        it.value.actions.size.toString()
                    }) {}

                }.render()
            })}
        }

        router.get("/devices").coroutineHandler { rc ->
            toHtml(rc) { clientId -> render(clientId, controllers.map {
                Table(it.asIterable(), cellpadding = "3").apply {
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
                        btn("restart"+it.value.ip, "Restart") {
                            it.value.send(Protocol.MsgBack.newBuilder().setReboot(true))
                            delay(100)
                            controllers.value = controllers.value - it.key
                        }
                    }) {}
                }.render()
            })}
        }

        router.get("/pullup").coroutineHandler { rc ->
            // val tp = redisAsync.incr(PULLUPS).await()
            val total = redisAsync.lpush(PULLUPS, Instant.now().toEpochMilli().toByteArray()).await()

            val ser = getSeries(100)

            val res = (ser.firstOrNull()?.refs?.size ?: 0).toString()

            pullups.value = retreivePullupsData()

            rc.okText(res)
        }

        val udpSocket = vertx.createDatagramSocket(DatagramSocketOptions())
        udpSocket.listen(89, "0.0.0.0") { asyncResult ->
            if (asyncResult.succeeded()) {
                udpSocket.handler { packet ->
                    val ip = packet.sender().host()!!

                    val msg = Protocol.Msg.parseFrom(packet.data().bytes) ?: error("Can't parse UDP message")

                    if (msg.hasDebugLogMessage()) {
                        println("log:>" + msg.debugLogMessage)
                    }

                    if (msg.hello != null && msg.hello.settings.isNotBlank() &&
                            (!controllers.value.contains(ip) ||
                            controllers.value[ip]!!.timeseq > msg.timeseq )) {
                        val sndr = udpSocket.sender(packet.sender().port(), packet.sender().host())

                        val newController = Controller(ip,
                                Json.decodeFromString<Esp8266Settings>(msg.hello.settings)
                        ) {
                            sndr.write(Buffer.buffer(it))
                        }
                        println("Controller ${ip}")
                        controllers.value = (controllers.value + (ip to newController))

                        async {
                            delay(2000)
                            println("========================")

                            newController.send(Protocol.MsgBack.newBuilder()
                                    .setBluePillMsg(Protocol.BluePill.newBuilder()
                                            .setContent(ByteString.copyFrom(
                                                    byteArrayOf(0, 1, 13, 0)
                                            )).build()))

                            newController.send(Protocol.MsgBack.newBuilder()
                                    .setBluePillMsg(Protocol.BluePill.newBuilder()
                                            .setContent(ByteString.copyFrom(
                                                    byteArrayOf(1, 1, 13, 0)
                                            )).build()))

                            val pins = listOf(
                                    1 to 9,
                                    1 to 8,
                                    //1 to 7,
                                    //1 to 6,
                                    1 to 4,
                                    1 to 3,
                                    //0 to 15,
                                    //0 to 12,
                                    //0 to 11
                                    // 0 to 10,
                                     0 to 9,
                                     0 to 8
                            )

                            pins.forEach {pin ->
                                val msg = byteArrayOf(0, pin.first.toByte(), pin.second.toByte(), 0)
                                newController.send(Protocol.MsgBack.newBuilder()
                                        .setBluePillMsg(Protocol.BluePill.newBuilder()
                                                .setContent(ByteString.copyFrom(msg)).build()))

                                println(">" + msg.asList().joinToString(" ") { it.toUByte().toString() })
                            }

                            var onoff: Int = 0

                            while (controllers.value.containsValue(newController)) {
                                pins.forEach { pin ->
                                    val msg = byteArrayOf(2, pin.first.toByte(), pin.second.toByte(), (if (onoff % 2 == 0) 60 else 5).toByte()) // (onoff % 2).toByte()
                                    println(">" + msg.asList().joinToString(" ") { it.toUByte().toString() })

                                    newController.send(Protocol.MsgBack.newBuilder()
                                            .setBluePillMsg(Protocol.BluePill.newBuilder()
                                                    .setContent(ByteString.copyFrom(msg)).build()))
                                }

                                if (onoff % 2 == 0)
                                    delay(6000)
                                else
                                    delay(6000)
                                onoff++
                            }
                        }

                        newController.send(Protocol.MsgBack.newBuilder()
                                .setUnixtime((System.currentTimeMillis() / 1000L).toInt()))
                    } else {
                        if (!controllers.value.contains(ip)) {
                            // Packet comes, but we don't know this controller.
                            // Let it introduce self first
                            udpSocket.send(Buffer.buffer(
                                    Protocol.MsgBack.newBuilder()
                                            .setId(42)
                                            .setIntroduceYourself(true)
                                            .build()
                                            .toByteArray()
                            ), packet.sender().port(), packet.sender().host()) {
                                // Do we need to do something here?
                            }
                        } else {
                            controllers.value[ip]!!.timeseq = msg.timeseq
                            async {
                                val now = Instant.now()
                                controllers.value[ip]!!.lastPacketAt = now
                                delay(6000)
                                if (controllers.value[ip]!!.lastPacketAt == now) {
                                    // For 6s we didn't receive anything.
                                    controllers.value -= ip
                                    return@async
                                }
                            }

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

        vertx.createHttpServer()
                .requestHandler(router)
                .webSocketHandler { ws ->
                    val clientId = if (ws.path().startsWith("/web/")) ClientId(ws.path().removePrefix("/web/").toInt()) else null
                    var wss = webSockets.value[clientId ?: ClientId(0)]

                    if (wss != null) {
                        ws.textMessageHandler {
                            if (it != null) {
                                launch {
                                    wss?.actions?.get(it!!)?.invoke()
                                }
                            }
                        } // chan = Channel<String>()
                        wss.sender = {
                            ws.writeTextMessage(it)
                        }

                        ws.closeHandler {
                            // Forget about this client
                            webSockets.value -= clientId!!
                        }
                        ws.accept()
                    } else {
                        println("UNKNOWN path:" + ws.uri())
                        ws.writeFinalTextFrame("location.reload()")
                        ws.close()
                    }
                }
                .listenAwait(port)
    }

    private suspend fun retreivePullupsData(): Sequence<Serie> =
        getSeries(redisAsync.llen(PULLUPS).await())

    data class Serie(val end: Instant, val refs: List<Long>)

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