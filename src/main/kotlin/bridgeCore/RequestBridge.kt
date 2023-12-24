package bridgeCore

import readUShort
import writeShort
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class RequestBridge: Bridge() {
    companion object {
        private const val RequestStoreLimit = Int.MAX_VALUE
        private const val ResponseStoreLimit = 256
        const val extraSignalSize = 1

        const val RqByteSignal:Byte = 2; const val RqShortSignal:Byte = 3
        const val ResByteSignal:Byte = 4; const val ResShortSignal:Byte = 5
    }
    private val requestMap = HashMap<Int,ByteArray?>(2)
    private val requestMapLock = ReentrantLock()
    private val requestMapCondition = requestMapLock.newCondition()

    private val responseMap = HashMap<Int,ByteArray?>(2)
    private val responseMapLock = ReentrantLock()
    private val responseMapCondition = responseMapLock.newCondition()

    protected abstract fun onRequest(reqId:Int, bf:ByteArray, size: Int):Boolean
    protected abstract fun onResponse(reqId:Int, bf:ByteArray, size: Int):Boolean

    private fun getFRqIdx(waitAndGet:Boolean = false):Int {
        responseMapLock.withLock {
            do{
                for (id in 1 until ResponseStoreLimit) {
                    if (!responseMap.containsKey(id)) {
                        responseMap[id] = null
                        return id
                    }
                }
                responseMapCondition.await()
            } while (waitAndGet && isBridgeAlive)
        }
        return -1
    }
    private fun getTRqIdx(reqId: Int):Int {
        if(reqId == 0) for (id in ResponseStoreLimit until RequestStoreLimit) {
            if (!requestMap.containsKey(id)) return id
        }
        else return reqId
        return -1
    }

    override fun getSignalSize(signal: Byte): Int {
        return when(signal) {
            RqByteSignal,ResByteSignal -> inStream!!.read()
            RqShortSignal,ResShortSignal -> inStream!!.readUShort()
            else -> -1
        }
    }
    override fun handleSignal(signal: Byte, bf: ByteArray, size: Int) {
        when(signal) {
            RqByteSignal,RqShortSignal -> {
                val dataSize = size - extraSignalSize
                val tempReqId = getTRqIdx(bf[0].toInt())
                if(onRequest(tempReqId, bf, dataSize)) requestMapLock.withLock {
                    requestMap[tempReqId] = ByteArray(dataSize) { bf[it + extraSignalSize] }
                    requestMapCondition.signalAll()
                }
            }
            ResByteSignal,ResShortSignal -> {
                val dataSize = size - extraSignalSize
                val reqId = bf[0].toInt()
                if(onResponse(reqId, bf, dataSize)) responseMapLock.withLock {
                    responseMap[reqId] = ByteArray(dataSize) { bf[it + extraSignalSize] }
                    responseMapCondition.signalAll()
                }
            }
        }
    }

    override fun startBridgeLooper(): Int {
        val because = super.startBridgeLooper()
        requestMapLock.withLock { requestMapCondition.signalAll() }
        responseMapLock.withLock { responseMapCondition.signalAll() }
        return because
    }

    fun sendRequest(bf:ByteArray, off:Int=0, len:Int=bf.size, willRespond:Boolean=false, waitAndSend:Boolean=true): Int {
        if(!isBridgeAlive || len >= 65535) return -1
        var reqId = 0
        if(willRespond) {
            reqId = getFRqIdx(waitAndSend)
            if(!isBridgeAlive) return -1
            else if(reqId < 0) throw RuntimeException(
                "Request WaitList Limit reached. " +
                "Wait for the Response from Previous Request before sending more request"
            )
        }
        try {
            sendData {
                val dataSecSize = len + extraSignalSize
                if (dataSecSize < 256) {
                    outStream!!.write(RqByteSignal.toInt())
                    outStream!!.write(dataSecSize)
                } else {
                    outStream!!.write(RqShortSignal.toInt())
                    outStream!!.writeShort(dataSecSize)
                }
                outStream!!.write(reqId)
                outStream!!.write(bf, off, len)
            }
        }
        catch (_: IOException){
            responseMapLock.withLock {
                responseMap.remove(reqId)
                responseMapCondition.signalAll()
            }
        }
        return reqId
    }
    fun sendResponse(forReqId:Int, bf:ByteArray, off:Int=0, len:Int=bf.size) {
        if(!isBridgeAlive || len >= 65535) return
        try {
            sendData {
                val dataSecSize = len + extraSignalSize
                if (dataSecSize < 256) {
                    outStream!!.write(RqByteSignal.toInt())
                    outStream!!.write(dataSecSize)
                } else {
                    outStream!!.write(RqShortSignal.toInt())
                    outStream!!.writeShort(dataSecSize)
                }
                outStream!!.write(forReqId)
                outStream!!.write(bf, off, len)
            }
        }
        catch (e: IOException){ e.printStackTrace() }
    }

    fun retrieveRequest(reqId:Int, canWaitFor:Long=1000000000):ByteArray? {
        if(reqId == 0 || reqId > RequestStoreLimit) return null

        requestMapLock.withLock {
            if(canWaitFor > 0L) {
                var timeLeft = canWaitFor*1000000
                while (
                    isBridgeAlive &&
                    !requestMap.containsKey(reqId) && timeLeft > 0
                ) timeLeft = requestMapCondition.awaitNanos(timeLeft)
            }
            return requestMap.remove(reqId)
        }
    }
    fun retrieveResponse(reqId:Int, canWaitFor:Long=1000000000):ByteArray? {
        if(reqId == 0 || reqId > ResponseStoreLimit) return null
        else if(!responseMap.containsKey(reqId)) return null

        responseMapLock.withLock {
            if(canWaitFor > 0L) {
                var timeLeft = canWaitFor*1000000
                while (
                    isBridgeAlive && timeLeft > 0 &&
                    responseMap.containsKey(reqId) && responseMap[reqId]==null
                ) timeLeft = responseMapCondition.awaitNanos(timeLeft)
            }
            return responseMap.remove(reqId)
        }
    }

    protected fun clearRequests() = requestMap.clear()
    protected fun clearResponse() = responseMap.clear()
}