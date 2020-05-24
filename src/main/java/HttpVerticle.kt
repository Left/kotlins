
import com.google.common.io.Resources
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    class WebSocket(val id: ClientId) {
        val openedAt = Instant.now()
        val actions = mutableMapOf<String, suspend () -> Unit>()
        var sender: (txt: String) -> Unit = {}
    }

    val webSockets = MutableStateFlow<Map<ClientId, WebSocket>>(mapOf())

    var globalIdx = 0
    var actionId = 0

    fun btn(clientId: ClientId, name:String, action: suspend () -> Unit): String {
        val btnId = actionId++.toString()
        val ws = webSockets.value[clientId]
        ws?.actions?.put(btnId, action)
        val jscodeToRun = "sock.send($btnId)"
        return "<button onclick='$jscodeToRun'>${name}</button>"
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

        val t = webSockets.value.toMutableMap()
        t[clientId] = WebSocket(clientId)
        webSockets.value = t

        val res = """
                |<html>
                |   <head>
                |       <title>Home server</title>
                |       <script>const _htmlClientId = ${clientId.v}</script>
                |       <script type='text/javascript'>${String(Resources.asByteSource(Resources.getResource("websocket.js")).read(), Charsets.UTF_8)}</script>
                |   </head>
                |   <body>
                |   ${body.invoke(clientId)}
                |   </body>
                |</html>
            """.trimMargin()

        rc.response().headers().add("Content-type", "text/html;charset=utf-8")
        rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
    }

    override suspend fun start() {
        val pullups = MutableStateFlow(retreivePullupsData())

        // Build Vert.x Web router
        val router = Router.router(vertx)

        router.get("/").coroutineHandler { rc ->
            rc.redirect("/pa")
        }

        router.get("/pa").coroutineHandler { rc ->
            // Pullups table
            toHtml(rc) { clientId -> render(clientId, pullups.map {
                Table(it.asIterable(), cellpadding = "3").apply {
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

                    column("-", {
                        btn(clientId, "-") {
                            val toDel = it.refs[it.refs.size/2]
                            redisAsync.lrem(PULLUPS, 0, toDel.toByteArray()).await()
                            pullups.value = retreivePullupsData()
                        } +
                        btn(clientId, "+") {
                            redisAsync.linsert(PULLUPS, true, it.refs[0].toByteArray(), (it.refs[0] - 1).toByteArray()).await()
                            pullups.value = retreivePullupsData()
                        }
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

        router.get("/js/:name").coroutineHandler { rc ->
            val n = rc.pathParam("name")

            val bs = Resources.asByteSource(Resources.getResource(n))
            var cont = bs.read()

            rc.response().headers().add("Content-type", "text/javascript;charset=utf-8")
            rc.response().end(Buffer.buffer(cont))
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
        udpSocket.listen(8081, "0.0.0.0") { asyncResult ->
            if (asyncResult.succeeded()) {
                udpSocket.handler { packet ->
                    println(packet.sender().host())

                    val msg = Protocol.Msg.parseFrom(packet.data().bytes)
                    if (msg.hello != null) {
                        // Let's connect
                        val d = Protocol.MsgBack.newBuilder()
                                .setId(12)
                                .setUnixtime((System.currentTimeMillis() / 1000L).toInt())
                                // .setIntroduceYourself(true)
                                .build()
                        val res =  d.toByteArray()

                        val sndr = udpSocket.sender(packet.sender().port(), packet.sender().host())
                        sndr.write(Buffer.buffer(res))
                    }
                    // println("PACKET: ${msg}")
                }
            } else {
                println("Listen failed")
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
                            val t = webSockets.value.toMutableMap()
                            t.remove(clientId)
                            webSockets.value = t
                        }
                        ws.accept()
                    } else {
                        println("UNKNOWN path:" + ws.uri())
                        ws.writeFinalTextFrame("WE DON't KNOW YOU")
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