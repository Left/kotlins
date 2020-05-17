import java.nio.ByteBuffer

fun Long.toByteArray() =
        ByteBuffer.allocate(java.lang.Long.BYTES)
                .putLong(this)
                .array()

fun ByteArray.toLong() =
        ByteBuffer.wrap(this)
                .getLong(0)