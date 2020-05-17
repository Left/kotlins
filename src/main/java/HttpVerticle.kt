
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
import java.time.Duration
import java.time.Instant

open class HttpVerticle(val port: Int) : CoroutineVerticle() {
    val client: RedisClient by lazy {
        RedisClient.create(RedisURI("127.0.0.1", 6379, Duration.ofMinutes(10)))
    }

    val redisAsync by lazy {
        client.connect(ByteArrayCodec()).async()
    }

    val PULLUPS = "totalPullups".toByteArray(Charsets.UTF_8)

    fun div(fn: () -> String): String = "<div>${fn.invoke()}</div>"

    override suspend fun start() {
        // Build Vert.x Web router
        val router = Router.router(vertx)

        router.get("/").coroutineHandler { rc ->
            val tp = redisAsync.incrby(PULLUPS, 0).await()

            val res = """
                |<html>
                |   <head>
                |       <title>Home server</title>
                |   </head>
                |   <body>
                |       ${div {"Now: ${Instant.now()}"} }
                |       ${div { tp.toString() } }
                |   </body>
                |</html>
            """.trimMargin()
            rc.response().headers().add("Content-type", "text/html;charset=utf-8")
            rc.response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
        }

        router.get("/pullup").coroutineHandler { rc ->
            val tp = redisAsync.incr(PULLUPS).await()

            val res = "OK: ${tp}"
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