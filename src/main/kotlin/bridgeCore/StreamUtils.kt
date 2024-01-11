/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package bridgeCore

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Defines an interface for a bidirectional communication channel.
 * It includes input and output streams along with methods to set a timeout and release resources.
 */
interface DuplexStream {
    val inStream: InputStream
    val outStream: OutputStream
    fun setTimeout(timeout: Int)
    fun releaseStream()
}

/**
 * An Bidirectional communication channel based on Tcp Sockets
 */
class SockStream(private val sock: Socket): DuplexStream {
    override val inStream: InputStream = sock.getInputStream()
    override val outStream: OutputStream = sock.getOutputStream()
    override fun setTimeout(timeout: Int) { sock.soTimeout = timeout }
    override fun releaseStream() = sock.close()

    companion object {
        fun Socket.toSockStream() = SockStream(this)
    }
}

/**
 * An dataclass which keeps track of stream availability
 */
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