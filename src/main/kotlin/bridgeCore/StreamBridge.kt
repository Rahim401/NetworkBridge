package bridgeCore

import getInt
import putInt
import writeData
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.lang.Exception
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class StreamBridge: Bridge() {
    var confMakeStmTimeout = confBeatInterBy4 / 2
    var confConnStmTimeout = confMakeStmTimeout / 5

    private val makeStmLock = ReentrantLock()
    private val makeStmCondition = makeStmLock.newCondition()
    private var stmList:ArrayList<RentableStream> = ArrayList()

    abstract fun makeStream(): DuplexStream?

    protected open val limitForStreams = 1024
    override fun updateConfInters(inter:Long){
        super.updateConfInters(inter)
        confMakeStmTimeout = confBeatInterBy4 / 2
        confConnStmTimeout = confMakeStmTimeout / 5
    }
    override fun getSignalSize(signal: Byte): Int {
        if (signal==MakeStmSignal) return 4
        return -1
    }
    override fun handleSignal(signal: Byte, bf: ByteArray, size: Int) {
        if (signal==MakeStmSignal && size==4) makeStmLock.withLock {
            if (stmList.size < limitForStreams) {
                var stmObj: DuplexStream? = null; val parityBits = bf.getInt()
                if(parityBits == stmList.size) try {
                    stmObj = makeStream()
                    if (stmObj != null) {
                        stmObj.outStream.write(bf, 0, 4)

                        stmObj.setTimeout(confMakeStmTimeout.toInt())
                        if(stmObj.inStream.readNBytes(bf,4,4) == 4) {
                            val actualBits = bf.getInt(4)
                            if (actualBits == parityBits) {
                                stmObj.setTimeout(0)
                                stmList.add(RentableStream(stmObj))
                                makeStmCondition.signalAll()
                                return
                            }
                        }
                    }
                }
                catch (e: InterruptedIOException){ e.printStackTrace() }
                catch (e: IOException){ e.printStackTrace() }
                catch (e: Exception){ e.printStackTrace() }
                stmObj?.releaseStream()
            }
        }
    }
    override fun startBridgeLooper(): Int {
        val res = super.startBridgeLooper()
        closeAllStreams()
        return res
    }

    fun makeAndConnStream(willSignal:Boolean=false): Int {
        if(!isBridgeAlive) return ErrorByBridgeNotAlive
        makeStmLock.withLock {
            if (stmList.size >= limitForStreams)
                return ErrorByStreamLimitReached
            else {
                var stmObj: DuplexStream? = null
                var causeOfError: Int
                val parityBits = stmList.size
                val dataBuffer = ByteArray(4)
                dataBuffer.putInt(0, parityBits)
                try {
                    sendData { writeData(MakeStmSignal, parityBits) }
                    stmObj = makeStream()
                    causeOfError = if (stmObj != null) {
                        stmObj.outStream.write(dataBuffer)

                        stmObj.setTimeout(confConnStmTimeout.toInt())
                        if (stmObj.inStream.readNBytes(dataBuffer, 0, 4) == 4) {
                            val actualBits = dataBuffer.getInt()
                            if (actualBits == parityBits) {
                                stmObj.setTimeout(0)
                                stmList.add(RentableStream(stmObj))
                                if (willSignal) makeStmCondition.signalAll()
                                return stmList.size - 1
                            }
                        }
                        ErrorOnStreamConnection
                    } else ErrorOnStreamCreation
                }
                catch (e: InterruptedIOException) { e.printStackTrace(); causeOfError = ErrorByConnectionTimeout; }
                catch (e: IOException) { e.printStackTrace(); causeOfError = ErrorByStreamClosed; }
                catch (e: Exception) { e.printStackTrace(); causeOfError = ErrorByUnexpectedException; }
                stmObj?.releaseStream()
                return causeOfError
            }
        }
    }
    fun isInStreamAvailable(stmId:Int) = stmId < stmList.size && stmList[stmId].isInStreamAvailable
    fun isOutStreamAvailable(stmId:Int) = stmId < stmList.size && stmList[stmId].isOutStreamAvailable
    fun setStreamTimeout(stmId: Int, timeout: Int) = stmList.getOrNull(stmId)?.streamObject?.setTimeout(timeout)

    fun acquireInStream(stmId:Int=-1, canCreate: Boolean=true, canWaitFor: Long=1000000000): Pair<Int, InputStream?> {
        var causeOfError: Int = ErrorByUnexpectedException
        makeStmLock.withLock {
            if (stmId < 0) {
                stmList.forEachIndexed { idx, rStm ->
                    if (rStm.isInStreamAvailable)
                        return Pair(idx, rStm.acquireInStream())
                }
                causeOfError = if (canCreate) {
                    val newStmId = makeAndConnStream()
                    if (newStmId >= 0) return Pair(
                        newStmId, stmList[newStmId]
                            .acquireInStream()
                    )
                    else newStmId // Error on creating Stream
                } else ErrorByStreamUnavailable // No ready InStream and Can't Create new
            }
            else {
                var timeLeft = canWaitFor*1000000
                while (isBridgeAlive && timeLeft > 0){
                    causeOfError = if(isInStreamAvailable(stmId))
                        return Pair(stmId, stmList[stmId].acquireInStream())
                    else ErrorByStreamUnavailable // InStream of stmId not available
                    timeLeft = makeStmCondition.awaitNanos(timeLeft)
                }
            }
        }
        return Pair(causeOfError, null)
    }
    fun releaseInStream(stmId:Int=-1) = makeStmLock.withLock {
        stmList.getOrNull(stmId)?.releaseInStream()
        makeStmCondition.signalAll()
    }
    inline fun withInStream(
        stmId:Int=-1, canCreate: Boolean=true, canWaitFor: Long=1000000000,
        block:(inpIdx:Int, inStream: InputStream?)->Unit
    ){
        val (inpIdx,inpStream) = acquireInStream(stmId, canCreate, canWaitFor)
        block(inpIdx,inpStream)
        releaseInStream(inpIdx)
    }

    fun acquireOutStream(stmId:Int=-1, canCreate: Boolean=true, canWaitFor: Long=1000000000): Pair<Int, OutputStream?> {
        var causeOfError: Int = ErrorByUnexpectedException
        makeStmLock.withLock {
            if (stmId < 0) {
                stmList.forEachIndexed { idx, rStm ->
                    if (rStm.isOutStreamAvailable)
                        return Pair(idx, rStm.acquireOutStream())
                }
                causeOfError = if (canCreate) {
                    val newStmId = makeAndConnStream()
                    if (newStmId >= 0) return Pair(
                        newStmId, stmList[newStmId]
                            .acquireOutStream()
                    )
                    else newStmId //Error on creating Stream
                } else ErrorByStreamUnavailable //No ready OutStream and Can't Create new
            }
            else {
                var timeLeft = canWaitFor*1000000
                while (isBridgeAlive && timeLeft > 0){
                    causeOfError = if(isOutStreamAvailable(stmId))
                        return Pair(stmId, stmList[stmId].acquireOutStream())
                    else ErrorByStreamUnavailable // InStream of stmId not available
                    timeLeft = makeStmCondition.awaitNanos(timeLeft)
                }
            }
        }
        return Pair(causeOfError, null)
    }
    fun releaseOutStream(stmId:Int=-1) = makeStmLock.withLock {
        stmList.getOrNull(stmId)?.releaseOutStream()
        makeStmCondition.signalAll()
    }
    inline fun withOutStream(
        stmId:Int=-1, canCreate: Boolean=true, canWaitFor: Long=1000000000,
        block:(outIdx:Int, outStream: OutputStream?)->Unit
    ){
        val (outIdx,outStream) = acquireOutStream(stmId, canCreate, canWaitFor)
        block(outIdx,outStream)
        releaseOutStream(outIdx)
    }

    private fun closeAllStreams() = makeStmLock.withLock {
        stmList.forEach { it.streamObject.releaseStream() }
        stmList.clear()
    }
}