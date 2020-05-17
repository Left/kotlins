
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
                |       <script>
                |       function httpGet(url) {
                |           let xhr = new XMLHttpRequest();
                |           xhr.open("GET", url, true);
                |           xhr.setRequestHeader("Content-Type", "application/json");
                |           xhr.onreadystatechange = function () {
                |               if (xhr.readyState === 4) {
                |                   if (xhr.status === 200) {
                |                       // alert("OK: " + xhr.responseText);
                |                       location.reload();
                |                   } else {
                |                       // alert("Error \"" + xhr.responseText + "\"");
                |                   } 
                |               }
                |           };
                |           xhr.send(JSON.stringify({ }));
                |       }
                |       </script>
                |   </head>
                |   <body>
                |       <table border=1 cellspacing=0>
                |           <thead>
                |               <tr>
                |                   <td align='center'>Дата</td>
                |                   <td align='center'>Время</td>
                |                   <td align='center'>Количество</td>
                |                   <td></td>
                |               </tr>
                |           </thead>
                |           <tbody>
                |           ${results.joinToString("\n") { 
                                    "<tr>" +
                                            "<td>" + dtf.format(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault())) + "</td>" +
                                            "<td>" + tmf.format(LocalDateTime.ofInstant(it.end, ZoneOffset.systemDefault())) + "</td>" +
                                            "<td align='right'>" + it.refs.size + "</td>" +
                                            "<td><button onclick='httpGet(\"/del?${it.refs.joinToString("&") { l -> "val=" + l }}\")'>Del</button></td>" +
                                    "</tr>" } }
                |           </tbody>
                |       </table>
                |   </body>
                |</html>
            """.trimMargin()
            rc.response().headers().add("Content-type", "text/html;charset=utf-8")
            rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
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