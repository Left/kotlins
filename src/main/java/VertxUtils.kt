
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext

fun RoutingContext.redirect(s: String) = response().putHeader("location", s).setStatusCode(302).end()

fun RoutingContext.okText(body: String) {
    response().headers().add("Content-type", "text/plain;charset=utf-8")
    response().end(Buffer.buffer(body.toByteArray(Charsets.UTF_8)))
}

var clientIdCntr = 1

suspend fun RoutingContext.toHtml(body: suspend (clientId: String) -> String) {
    val clientId = (clientIdCntr++).toString()
    val res = """
                |<html>
                |   <head>
                |       <title>Home server</title>
                |       <script>const _htmlClientId = ${clientId}</script>
                |       <script type='text/javascript' src='js/websocket.js'></script>
                |       <script type='text/javascript' src='js/httpsend.js'></script>
                |   </head>
                |   <body>
                |   ${body.invoke(clientId)}
                |   </body>
                |</html>
            """.trimMargin()

    response().headers().add("Content-type", "text/html;charset=utf-8")
    response().end(Buffer.buffer(res.toByteArray(Charsets.UTF_8)))
}

