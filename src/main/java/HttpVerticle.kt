
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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*

open class HttpVerticle(val port: Int) : CoroutineVerticle() {
    val client: RedisClient by lazy {
        RedisClient.create(RedisURI("127.0.0.1", 6379, Duration.ofMinutes(10)))
    }

    val redisAsync by lazy {
        client.connect(ByteArrayCodec()).async()
    }

    val PULLUPS = "pullups".toByteArray(Charsets.UTF_8)

    class WebSocket(val name: String) {
        val outgoingEvents = Channel<String>()
        val incomingEvents = Channel<String>()
        val openedAt = Instant.now()
        var lastWrite: Instant = Instant.MIN
    }

    val webSockets = MutableStateFlow<List<WebSocket>>(listOf())

    var globalIdx = 0;

    suspend fun render(clientId: String, fl: Flow<String>): String {
        val idx = globalIdx++
        val ret = withTimeoutOrNull(1) {
            fl.take(1).single()
        }

        launch {
            fl.collect { value ->
                val ws = webSockets.value.firstOrNull { it.name == clientId }
                ws?.outgoingEvents?.send("document.getElementById('v${idx}').innerHTML = '${value.replace("\n", "\\n").replace("'", "\\'")}'");
            }
        }

        return "<span id='v${idx}'>${ret ?: "NO DATA"}</span>"
    }

    val DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))
    val TIME_FORMATTER = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))

    override suspend fun start() {
        val pullups = MutableStateFlow(retreivePullupsData())

        // Build Vert.x Web router
        val router = Router.router(vertx)

        router.get("/").coroutineHandler { rc ->
            rc.redirect("/pa")
        }

        router.get("/pa").coroutineHandler { rc ->
            // Pullups table
            rc.toHtml { clientId -> render(clientId, pullups.map {
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
                        "<button onclick='sock.send([${it.refs.joinToString(",")}])'>Del</button>"
                    }) {}
                }.render()
            })}
        }

        router.get("/ws").coroutineHandler { rc ->
            rc.toHtml { clientId -> render(clientId, webSockets.map {
                Table(it.asIterable(), cellpadding = "3").apply {
                    column("Id", {
                        it.name
                    }) {}
                    column("Opened", {
                        it.openedAt.atZone(ZoneId.systemDefault()).toString()
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

        router.get("/del").coroutineHandler { rc ->
            val values = rc.request().params().getAll("val")

            values.map {
                async {
                    redisAsync.lrem(PULLUPS, 0, it.toLong().toByteArray()).await()
                }}.awaitAll()

            pullups.value = retreivePullupsData()

            rc.okText("")
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
                    if (ws.path().startsWith("/web/")) {
                        val clientId = ws.path().removePrefix("/web/")
                        var wss: WebSocket? = null
                        ws.textMessageHandler {
                            if (it != null) {
                                launch {
                                    wss!!.incomingEvents.send(it!!)
                                }
                            }
                        } // chan = Channel<String>()
                        wss = WebSocket(clientId)
                        launch {
                            wss.outgoingEvents.consumeEach {
                                wss.lastWrite = Instant.now()
                                ws.writeTextMessage(it)
                            }
                        }
                        webSockets.value = webSockets.value + wss

                        ws.closeHandler {
                            webSockets.value = webSockets.value.filter { it.name != clientId }
                        }
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