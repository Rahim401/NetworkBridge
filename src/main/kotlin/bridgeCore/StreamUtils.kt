package bridgeCore

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

interface DuplexStream {
    val inStream: InputStream
    val outStream: OutputStream
    fun setTimeout(timeout: Int)
    fun releaseStream()
}

class SockStream(private val sock: Socket): DuplexStream {
    override val inStream: InputStream = sock.getInputStream()
    override val outStream: OutputStream = sock.getOutputStream()
    override fun setTimeout(timeout: Int) { sock.soTimeout = timeout }
    override fun releaseStream() = sock.close()

    companion object {
        fun Socket.toSockStream() = SockStream(this)
    }
}
data class RentableStream(val streamObject: DuplexStream) {
    var isInStreamAvailable = true
        private set
    var isOutStreamAvailable = true
        private set

    fun acquireInStream(): InputStream? = synchronized(this) {
        if (isInStreamAvailable) {
            isInStreamAvailable = false
            return streamObject.inStream
        }
        return null
    }
    fun releaseInStream() = synchronized(this) { isInStreamAvailable = true }

    fun acquireOutStream(): OutputStream? = synchronized(this) {
        if(isOutStreamAvailable){
            isOutStreamAvailable = false
            return streamObject.outStream
        }
        return null
    }
    fun releaseOutStream() = synchronized(this) { isOutStreamAvailable = true }
}