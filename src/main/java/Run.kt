import io.vertx.core.Vertx

fun main() {
    val vertx = Vertx.vertx()
    println("Starting http")
    val srv = HttpVerticle(81)
    vertx.deployVerticle(srv)
    println("Started http")
}