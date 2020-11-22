import com.google.common.hash.Hashing

enum class HAlign {
    LEFT,
    CENTER,
    RIGHT
}

fun ByteArray.toColor() = take(3)
        .map { (it.toUByte() or 0xC0.toUByte()).toString(16).padStart(2, '0') }
        .joinToString("")

fun colorFor(value: String): String {
    return "#" + Hashing.sha256()
            .newHasher()
            .putString(value, Charsets.UTF_8)
            .hash()
            .asBytes()
            .toColor()
}

class Column<Row>(val name: String, val render: (Row) -> String) {
    var tdAlign: (Row) -> HAlign = { HAlign.LEFT }
    var tdBgColor: (Row) -> String = { "" }
}

class Table<Row>(
        val rows: Iterable<Row>,
        val header: Boolean = true,
        val cellpadding: String = "2",
        val cellspacing: String = "0",
        val border: String = "1",
        val width: String = "",
        val bgColor: ((Row) -> String)? = null
) {
    val columnsMap = mutableListOf<Column<Row>>()

    fun column(name: String, render: (Row) -> String, init: Column<Row>.() -> Unit) : Column<Row> {
        val head = Column<Row>(name, render)
        head.init()
        columnsMap.add(head)
        return head
    }

    fun render(): String {
        val attrs = mapOf(
                "border" to this.border,
                "cellpadding" to this.cellpadding,
                "cellspacing" to this.cellspacing,
                "width" to this.width
        )

        return "<table ${attrs.entries.filter { it.value.isNotEmpty() }.joinToString(" ") { "${it.key} = '${it.value}'" } }>" +
                if (this.header) {
                    "<tr>" + this.columnsMap.joinToString("") { column ->
                        "<th>" + column.name + "</th>"
                    } + "</tr>\n"
                } else {
                    ""
                } +
                this.rows.joinToString("\n") { item ->
                    "<tr style='${
                        listOf("background-color" to bgColor)
                                .filter { it.second != null }
                                .joinToString(";") { it.first + ":" + it.second?.invoke(item) }
                    }'>" + columnsMap.map { column ->
                        val text = column.render(item)

                        val attrs = listOf(
                                "align" to column.tdAlign(item).name,
                                "bgcolor" to column.tdBgColor(item)
                        )

                        "<td ${attrs.filter { it.second.isNotBlank() }
                                .joinToString(" ") { "${it.first}='${it.second}'" }}>$text</td>"
                    }.joinToString(" ") + "</tr>\n"
                } +
                "</table>"
    }
}