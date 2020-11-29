
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.Router
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

fun HttpVerticle.pullups(router: Router) {
    val pullupsTable = Table<HttpVerticle.Serie>(
            bgColor = { colorFor(it.time.dayOfYear.toString()) },
            cellpadding = "3").apply {
        column("Дата", {
            DATE_FORMATTER.format(it.time)
        }) {}

        column("Время", {
            TIME_FORMATTER.format(it.time)
        }) {}

        column("Количество", {
            it.refs.size.toString()
        }) {
            tdAlign = { HAlign.RIGHT }
        }

        if (true) {
            column("+/-", {
                if (Instant.now().minus(Duration.ofDays(2)) < it.end) {
                    btn("minus" + it.refs[it.refs.size / 2], "-") {
                        val toDel = it.refs[it.refs.size / 2]
                        redisAsync.lrem(PULLUPS, 0, toDel.toByteArray()).await()
                        pullups.value = retreivePullupsData()
                    } +
                            btn("plus" + it.refs[0], "+") {
                                redisAsync.linsert(PULLUPS, true, it.refs[0].toByteArray(), (it.refs[0] - 1).toByteArray()).await()
                                pullups.value = retreivePullupsData()
                            }
                } else {
                    ""
                }
            }) {}
        }
    }

    router.get("/pullup").coroutineHandler { rc ->
        rc.okText(pullupPerformed())
    }


    router.get("/boom").coroutineHandler { rc ->
        // udp

        playSoundTemp(rc.request().getParam("i").toIntOrNull() ?: 549)

        rc.okText("Boom")
    }

    router.get("/pa").coroutineHandler { rc ->
        val allowEdit = rc.request().getParam("edit") == "on"

        // Pullups table
        toHtml(rc) { clientId ->
            dynamic(pullups.map {
                pullupsTable.render(it.asIterable())
            })
        }
    }
}

internal suspend fun HttpVerticle.playSoundTemp(index: Int) {
    val sndr = udpSocket.sender(8081, "192.168.1.35")

    val ldt = LocalDateTime.now()
    if (ldt.hour in 8..22) {
        sndr.write(Buffer.buffer(Protocol.MsgBack.newBuilder()
                .setVolume(50)
                .setId(2044)
                .build()
                .toByteArray()))

        delay(100)

        sndr.write(Buffer.buffer(Protocol.MsgBack.newBuilder()
                .setPlayMp3(index)
                .setId(2045)
                .build()
                .toByteArray()))
    }
}
