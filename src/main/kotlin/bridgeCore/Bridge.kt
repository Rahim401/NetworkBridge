/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package bridgeCore

import readByte
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

/**
 * This is the baseclass of all bridges, which provides the basic functionalities like
 * **Keeping Connection alive**, **Reading and dispatching various Signals**, **Disconnection** and more
 *
 * And these bridges only dictates the ways to communicate and Won't care how it gets these streams.
 * So, its up to the extending class, how it makes connections and get the stream
*/
abstract class Bridge {
    companion object {
        const val DefaultBeatInterval = 1000L
        const val DefaultDataBufferSize = 4096
        const val StoppedBySelf = 0
        const val StoppedByPeer = 1
    }

    protected var isBridgeAlive: Boolean = false
        private set

    protected abstract var inStream: InputStream?
    protected abstract var outStream: OutputStream?

    /** Sets the timeout of the `inStream` */
    protected abstract fun setTimeout(timeout:Long)
    /**
     * Need to return the data size of extended Signal we are currently reading.
     * Will be called inside the looper, so if it takes long, connection might get disconnected
     * @return size of the signal and -1 if signal invalid
     * */
    protected abstract fun getSignalSize(signal:Byte): Int
    /**
     * Will be called to handle extended signals inside the looper, therefore should not perform any
     * long running operations in it.
     * @param signal the signal code in range of -128 to 127
     * @param bf an temporary buffer with the data of the Signal
     * @param size the size of the data
     */
    protected abstract fun handleSignal(signal:Byte, bf:ByteArray, size:Int)

    protected var confBeatInter = DefaultBeatInterval
    protected var confBeatInterBy4 = confBeatInter / 4
    /** Method to update the interval between Keep-Alive Signals */
    protected open fun updateConfInters(inter:Long=DefaultBeatInterval){
        confBeatInter = inter
        confBeatInterBy4 = confBeatInter / 4
    }

    /** Variables used to keep track of time since keep-alive beats */
    protected var nextSBeatAt = System.currentTimeMillis()
    protected var nextRBeatAt = System.currentTimeMillis() + confBeatInter

    /**
     * This is the main looper which needed to be called after initializing streams
     * and from there it will take control and blocks that thread until getting stopped
     * **by self** with `stopBridgeLooper` or **by peer** or **by some error**
     * @return reason why bridge stopped
     */
    protected open fun startBridgeLooper():Int {
        if(isBridgeAlive) return ErrorByBridgeAlreadyAlive // Because of Invalid State
        var stoppedBecauseOf: Int = ErrorByUnexpectedException
        try {
            isBridgeAlive = true
            setTimeout(confBeatInterBy4)

            sendData { outStream!!.write(StartLooperSignal.toInt()) }
            nextRBeatAt = System.currentTimeMillis() + confBeatInter

            var initialSignal = -1
            while (System.currentTimeMillis() < nextRBeatAt) {
                try{ initialSignal = inStream!!.read(); break }
                catch (e:InterruptedIOException) {}
            }
            if(initialSignal == StartLooperSignal.toInt()){
                var timeNow = System.currentTimeMillis()
                nextSBeatAt = timeNow
                nextRBeatAt = timeNow + confBeatInter

                var currentSignal:Byte = -1
                var currentReadState = 0; var dataSize = 0; var dataToRead = 0
                val dataBuf = ByteArray(DefaultDataBufferSize)

                while (isBridgeAlive) {
                    try {
                        if (currentReadState == 0) {
                            currentSignal = inStream!!.readByte()
                            currentReadState = 1
                        }

                        if (currentSignal != BeatSignal && currentSignal != StopLooperSignal) {
                            if (currentReadState == 1) {
                                dataSize = getSignalSize(currentSignal)
                                if(dataSize < 0){
                                    stoppedBecauseOf = ErrorByUnintendedSignal // Because of Unintended Signal
                                    break
                                }
                                dataToRead = dataSize; currentReadState = 2
                            }

                            if (currentReadState == 2) {
                                val offset = dataSize - dataToRead
                                dataToRead -= inStream!!.readNBytes(dataBuf, offset, dataToRead)
                            }
                        }

                        if (dataToRead == 0) currentReadState = 3
                        nextRBeatAt = System.currentTimeMillis() + confBeatInter
                    }
                    catch (e: InterruptedIOException) {}

                    timeNow = System.currentTimeMillis()
                    if (timeNow >= nextRBeatAt) {
                        stoppedBecauseOf = ErrorByNetworkTimeout // Because of Network
                        break
                    }
                    if (timeNow >= nextSBeatAt) sendData {
                        outStream!!.write(BeatSignal.toInt())
                    }

                    if (currentReadState == 3) {
                        when (currentSignal) {
                            BeatSignal -> {}
                            StopLooperSignal -> {
                                stoppedBecauseOf = StoppedByPeer // Because of Peer Stop
                                break
                            }
                            else -> handleSignal(currentSignal, dataBuf, dataSize)
                        }
                        currentReadState = 0
                    }
                }
                if(!isBridgeAlive) stoppedBecauseOf = StoppedBySelf // Because of Self Stop
            } else stoppedBecauseOf = ErrorByUnintendedSignal // Because of UnReliable Connection
        }
        catch (e: IOException) { stoppedBecauseOf = ErrorByStreamClosed  } // Because of Stream Closed
        catch (e: Exception) { stoppedBecauseOf = ErrorByUnexpectedException } // Because of Unknown Error
        isBridgeAlive = false
        return stoppedBecauseOf
    }
    protected open fun stopBridgeLooper() {
        if(!isBridgeAlive) return
        try { sendData { outStream!!.write(StopLooperSignal.toInt()) } }
        catch (e: IOException) {}
        isBridgeAlive = false
    }

    /**
     * An generic method to send data to the peer in a thread safe way
     */
    protected inline fun sendData(block:OutputStream.()->Unit) = synchronized(outStream!!) {
        block(outStream!!)
        nextSBeatAt = System.currentTimeMillis() + confBeatInterBy4
    }
}