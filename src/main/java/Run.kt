import io.vertx.core.Vertx
import io.vertx.kotlin.core.deployVerticleAwait
import kotlinx.coroutines.runBlocking

fun main() = runBlocking{
    val vertx = Vertx.vertx()
    println("Starting http")
    val port = 81
    val srv = HttpVerticle(port)
    println("Starting http on http://127.0.0.1:${port}")
    vertx.deployVerticleAwait(srv)
    println("Server is started")
}