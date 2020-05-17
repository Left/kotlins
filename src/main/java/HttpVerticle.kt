
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
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun Long.toByteArray() =
        ByteBuffer.allocate(java.lang.Long.BYTES)
                .putLong(this)
                .array()

fun ByteArray.toLong() =
        ByteBuffer.wrap(this)
                .getLong(0)

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
            // val tp = redisAsync.incrby(PULLUPS, 0).await()
            val results = getSeries(10000)
            val dtf = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)!!
            val tmf = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM);

            val res = """
                |<html>
                |   <head>
                |       <title>Home server</title>
                |   </head>
                |   <body>
                |       <table border=1 cellspacing=0>
                |           <thead>
                |               <tr>
                |                   <td>Дата</td>
                |                   <td>Время</td>
                |                   <td>Количество</td>
                |               </tr>
                |           </thead>
                |           <tbody>
                |           ${results.joinToString("\n") { 
                                    "<tr>" +
                                            "<td>" + dtf.format(LocalDateTime.ofInstant(it.first, ZoneOffset.systemDefault())) + "</td>" +
                                            "<td>" + tmf.format(LocalDateTime.ofInstant(it.first, ZoneOffset.systemDefault())) + "</td>" +
                                            "<td align='right'>" + it.second + "</td>" +
                                    "</tr>" } }
                |           </tbody>
                |       </table>
                |   </body>
                |</html>
            """.trimMargin()
            rc.response().headers().add("Content-type", "text/html;charset=utf-8")
            rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
        }

        router.get("/pullup").coroutineHandler { rc ->
            // val tp = redisAsync.incr(PULLUPS).await()
            val total = redisAsync.lpush(PULLUPS, Instant.now().toEpochMilli().toByteArray()).await()

            val ser = getSeries(100)

            val res = (ser.firstOrNull()?.second ?: 0).toString()
            rc.response().headers().add("Content-type", "text/plain;charset=utf-8")
            rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
        }
/*
        router.get("/google0a69acc9fb7125a6.html").coroutineHandler { rc ->
            rc.response().headers().add("Content-type", "text/plain;ch  arset=utf-8")
            rc.response().end(Buffer.buffer(("google-site-verification: google0a69acc9fb7125a6.html").toByteArray(Charsets.UTF_8)))
        }
*/
        vertx.createHttpServer()
                .requestHandler(router)
                .listenAwait(port)
    }

    private suspend fun getSeries(max: Long = 1000): Sequence<Pair<Instant, Int>> {
        val tp = redisAsync.lrange(PULLUPS, 0, max).await()
        if (tp.isEmpty()) {
            return emptySequence()
        }
        val times = tp.asSequence().map { Instant.ofEpochMilli(it.toLong())!! }
        return sequence<Pair<Instant, Int>> {
            var prev = times.first()
            var cnt = 1
            times.drop(1).forEach {
                // 30 seconds means starting new series
                if (prev.toEpochMilli() - it.toEpochMilli() > 30_000) {
                    yield(prev to cnt)
                    cnt = 1
                } else {
                    cnt++
                }
                prev = it
            }
            yield(prev to cnt)
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