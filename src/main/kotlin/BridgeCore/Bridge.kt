package BridgeCore

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.SocketException
import java.net.SocketTimeoutException

abstract class Bridge {
    protected var isBridgeAlive: Boolean = false
        private set

    protected abstract var inLane: DataInputStream?
    protected abstract var outLane: DataOutputStream?
    abstract fun setInStreamTimeout(timeout:Long)

    abstract fun getSignalSize(signal:Byte): Int
    abstract fun handleSignal(signal:Byte, size:Int, buffer:ByteArray)

    var confBeatInter = defaultBeatInterval
    var confBeatInterBy4 = confBeatInter / 4
    protected open fun updateConfInters(inter:Long=defaultBeatInterval){
        confBeatInter = inter
        confBeatInterBy4 = confBeatInter / 4
    }

    var nextSBeatAt = System.currentTimeMillis()
    var nextRBeatAt = System.currentTimeMillis() + confBeatInter
    protected open fun startBridgeLooper():Int {
        if(isBridgeAlive) return ErrorByBridgeAlive // Because of Invalid State

        var stoppedBecauseOf: Int = -1
        try {
            isBridgeAlive = true
            setInStreamTimeout(confBeatInterBy4)

            sendData { outLane!!.write(StartLooperSignal.toInt()) }
            nextRBeatAt = System.currentTimeMillis() + confBeatInter

            var initialSignal = -1
            while (System.currentTimeMillis() < nextRBeatAt) {
                try{ initialSignal = inLane!!.read(); break }
                catch (e:SocketTimeoutException) {}
            }
            if(initialSignal == StartLooperSignal.toInt()){
                var timeNow = System.currentTimeMillis()
                nextSBeatAt = timeNow
                nextRBeatAt = timeNow + confBeatInter

                var currentSignal:Byte = -1;
                var currentReadState = 0; var dataSize = 0; var dataToRead = 0
                val dataBuf = ByteArray(4096)

                while (isBridgeAlive) {
                    try {
                        if (currentReadState == 0) {
                            val maskedSignal = inLane!!.read()
                            currentSignal = (maskedSignal and 15).toByte()
                            currentReadState = 1
                        }

                        if (currentSignal in 2..14) {
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
                                dataToRead -= inLane!!.readNBytes(dataBuf, offset, dataToRead)
                            }
                        }

                        if (dataToRead == 0) currentReadState = 3
                        nextRBeatAt = System.currentTimeMillis() + confBeatInter
                    } catch (e: SocketTimeoutException) {}

                    timeNow = System.currentTimeMillis()
                    if (timeNow >= nextRBeatAt) {
                        stoppedBecauseOf = ErrorByNetworkTimeout // Because of Network
                        break
                    }
                    if (timeNow >= nextSBeatAt) sendData {
                        outLane!!.write(BeatSignal.toInt())
                    }

                    if (currentReadState == 3) {
                        when (currentSignal) {
                            BeatSignal -> {}
                            StopLooperSignal -> {
                                stoppedBecauseOf = StoppedByPeer // Because of Peer Stop
                                break
                            }
                            else -> handleSignal(
                                currentSignal,
                                dataSize, dataBuf
                            )
                        }
                        currentReadState = 0
                    }
                }
                if(!isBridgeAlive) stoppedBecauseOf = StoppedBySelf // Because of Self Stop
            } else stoppedBecauseOf = ErrorByUnreliableConnection // Because of UnReliable Connection
        }
        catch (e: SocketException) { stoppedBecauseOf = ErrorByStreamClosed  } // Because of Stream Closed
        catch (e: Exception) { stoppedBecauseOf = ErrorByUnexpectedException } // Because of Unknown Error
        isBridgeAlive = false
        return stoppedBecauseOf
    }
    protected open fun stopBridgeLooper() {
        if(!isBridgeAlive) return
        try { sendData { outLane!!.write(StopLooperSignal.toInt()) } }
        catch (e: SocketException) {}
        isBridgeAlive = false
    }

    protected inline fun sendData(block:()->Unit) = synchronized(outLane!!){
        block()
        nextSBeatAt = System.currentTimeMillis() + confBeatInterBy4
    }
}