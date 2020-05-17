
import com.google.common.io.Resources
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
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

    fun div(fn: () -> String): String = "<div>${fn.invoke()}</div>"

    override suspend fun start() {
        // Build Vert.x Web router
        val router = Router.router(vertx)

        router.get("/").coroutineHandler { rc ->
            val listLen = redisAsync.llen(PULLUPS).await()
            val results = getSeries(listLen)
            val dtf = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))
            val tmf = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)!!.withLocale(Locale("ru"))

            val table =
                    Table(results.asIterable(), cellpadding = "3").apply {
                        column("Дата", {
                            dtf.format(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault()))
                        }) {}

                        column("Время", {
                            tmf.format(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault()))
                        }) {}

                        column("Количество", {
                            it.refs.size.toString()
                        }) {
                            tdAlign = { HAlign.RIGHT }
                        }

                        column("-", {
                            "<button onclick='httpGet(\"/del?${it.refs.joinToString("&") { l -> "val=" + l }}\")'>Del</button>"
                        }) {}
                    }.render()

            val res = """
                |<html>
                |   <head>
                |       <title>Home server</title>
                |       <script type='text/javascript' src='js/websocket.js'></script>
                |       <script type='text/javascript' src='js/httpsend.js'></script>
                |   </head>
                |   <body>
                |   $table
                |   </body>
                |</html>
            """.trimMargin()

            rc.response().headers().add("Content-type", "text/html;charset=utf-8")
            rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
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

            rc.response().headers().add("Content-type", "text/plain;charset=utf-8")
            rc.response().end(Buffer.buffer("".toByteArray(Charsets.UTF_8)))
        }

        router.get("/pullup").coroutineHandler { rc ->
            // val tp = redisAsync.incr(PULLUPS).await()
            val total = redisAsync.lpush(PULLUPS, Instant.now().toEpochMilli().toByteArray()).await()

            val ser = getSeries(100)

            val res = (ser.firstOrNull()?.refs?.size ?: 0).toString()
            rc.response().headers().add("Content-type", "text/plain;charset=utf-8")
            rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
        }

        vertx.createHttpServer()
                .requestHandler(router)
                .webSocketHandler { ws ->
                    if (ws.path() == "/web") {
                        ws.textMessageHandler {
                            if (it != null) {
                                ws.writeTextMessage(it!!)
                            }
                        }
                    } else {
                        println("UNKNOWN path:" + ws.uri())
                        ws.writeFinalTextFrame("WE DON't KNOW YOU")
                        ws.close()
                    }
                }
                .listenAwait(port)
    }

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