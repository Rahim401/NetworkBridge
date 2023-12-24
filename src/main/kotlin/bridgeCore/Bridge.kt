package bridgeCore

import readByte
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

abstract class Bridge {
    companion object {
        const val defaultBeatInterval = 1000L

        const val StartLooperSignal: Byte = 0
        const val BeatSignal: Byte = 1
        const val StopLooperSignal: Byte = -1

        const val StoppedBySelf = 0
        const val StoppedByPeer = 1
        const val ErrorByBridgeAlreadyAlive = -1
        const val ErrorByUnreliableConnection = -2
        const val ErrorByNetworkTimeout = -3
        const val ErrorByUnintendedSignal = -4
        const val ErrorByStreamClosed = -5
        const val ErrorByUnexpectedException = -6
    }

    protected var isBridgeAlive: Boolean = false
        private set

    protected abstract var inStream: InputStream?
    protected abstract var outStream: OutputStream?
    protected abstract fun setTimeout(timeout:Long)
    protected abstract fun getSignalSize(signal:Byte): Int
    protected abstract fun handleSignal(signal:Byte, bf:ByteArray , size:Int)

    protected var confBeatInter = defaultBeatInterval
    protected var confBeatInterBy4 = confBeatInter / 4
    protected open fun updateConfInters(inter:Long=defaultBeatInterval){
        confBeatInter = inter
        confBeatInterBy4 = confBeatInter / 4
    }

    protected var nextSBeatAt = System.currentTimeMillis()
    protected var nextRBeatAt = System.currentTimeMillis() + confBeatInter
    protected open fun startBridgeLooper():Int {
        if(isBridgeAlive) return ErrorByBridgeAlreadyAlive // Because of Invalid State

        var stoppedBecauseOf: Int = -1
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
                val dataBuf = ByteArray(4096)

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
            } else stoppedBecauseOf = ErrorByUnreliableConnection // Because of UnReliable Connection
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

    protected inline fun sendData(block:OutputStream.()->Unit) = synchronized(outStream!!) {
        block(outStream!!)
        nextSBeatAt = System.currentTimeMillis() + confBeatInterBy4
    }
}