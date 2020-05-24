
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext

fun RoutingContext.redirect(s: String) = response().putHeader("location", s).setStatusCode(302).end()

fun RoutingContext.okText(body: String) {
    response().headers().add("Content-type", "text/plain;charset=utf-8")
    response().end(Buffer.buffer(body.toByteArray(Charsets.UTF_8)))
}

