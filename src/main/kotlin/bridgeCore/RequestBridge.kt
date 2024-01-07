/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package bridgeCore

import awaitTill
import readUShort
import toPInt
import writeShort
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class RequestBridge: Bridge() {
    private val requestMap = HashMap<Int,ByteArray?>(2)
    private val requestMapLock = ReentrantLock()
    private val requestMapCondition = requestMapLock.newCondition()

    private val responseMap = HashMap<Int,ByteArray?>(2)
    private val responseMapLock = ReentrantLock()
    private val responseMapCondition = responseMapLock.newCondition()

    protected open val sizeOfPacket = 65535
    protected open val limitOfResId = 256
    protected open val limitOfReqId = 1024
    protected open val sizeOfResId = 1
    protected open fun writeResId(outStm:OutputStream, resId:Int) = outStm.write(resId)
    protected open fun readResId(bf:ByteArray, pos:Int) = bf[pos].toPInt()

    protected abstract fun onRequest(resId:Int, reqId:Int, bf:ByteArray, size: Int):Boolean
    protected abstract fun onResponse(resId:Int, bf:ByteArray, size: Int):Boolean

    private var resIdUsedCount = 1; private var reqIdUsedCount = 0
    private fun acquireResId(canWaitFor:Long=0):Int = responseMapLock.withLock {
        var timeLeft = TimeUnit.MILLISECONDS.toNanos(canWaitFor)
        while (isBridgeAlive) {
            if(resIdUsedCount < limitOfResId) {
                for (id in 1 until limitOfResId) {
                    if (!responseMap.containsKey(id)) {
                        responseMap[id] = null
                        resIdUsedCount++
                        return id
                    }
                }
            }
            if (timeLeft > 0) timeLeft = responseMapCondition.awaitNanos(timeLeft)
            else return@withLock ErrorByLimitReached
        }
        return@withLock ErrorByBridgeNotAlive
    }
    private fun releaseResId(resId: Int):ByteArray? {
        responseMapLock.lock()
        try {
            resIdUsedCount--
            return responseMap.remove(resId)
        }
        finally {
            responseMapCondition.signalAll()
            responseMapLock.unlock()
        }
    }
    private fun acquireReqId(canWaitFor:Long=0):Int = requestMapLock.withLock {
        var timeLeft = TimeUnit.MILLISECONDS.toNanos(canWaitFor)
        while (isBridgeAlive) {
            if(reqIdUsedCount < limitOfReqId) {
                for (id in 0 until limitOfReqId) {
                    if (!requestMap.containsKey(id)) {
                        requestMap[id] = null
                        reqIdUsedCount++
                        return id
                    }
                }
            }
            if (timeLeft > 0) timeLeft = requestMapCondition.awaitNanos(timeLeft)
            else return@withLock ErrorByLimitReached
        }
        return@withLock ErrorByBridgeNotAlive
    }
    private fun releaseReqId(reqId: Int):ByteArray? {
        requestMapLock.lock()
        try {
            reqIdUsedCount--
            return requestMap.remove(reqId)
        }
        finally {
            requestMapCondition.signalAll()
            requestMapLock.unlock()
        }
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
                val reqId = acquireReqId(0L)
                val dataSize = size - sizeOfResId
                val resId = readResId(bf, dataSize)
                if (onRequest(resId, reqId, bf, dataSize)) requestMapLock.withLock {
                    requestMap[reqId] = ByteArray(size) { bf[it] }
                    requestMapCondition.signalAll()
                }
                else if(reqId >= 0) releaseReqId(reqId)
            }
            ResByteSignal,ResShortSignal -> {
                val dataSize = size - sizeOfResId
                val resId = readResId(bf, dataSize)
                if(onResponse(resId, bf, dataSize)) responseMapLock.withLock {
                    responseMap[resId] = ByteArray(size) { bf[it] }
                    responseMapCondition.signalAll()
                }
                else if(resId > 0) releaseResId(resId)
            }
        }
    }

    override fun startBridgeLooper(): Int {
        val because = super.startBridgeLooper()
        clearRequests(); clearResponses()
        return because
    }

    fun sendRequest(bf:ByteArray, off:Int=0, len:Int=bf.size, willRespond:Boolean=false, canWaitFor:Long=Long.MAX_VALUE): Int {
        if(!isBridgeAlive) return ErrorByBridgeNotAlive
        else if(len < 0 || len >= sizeOfPacket) return ErrorByDataSizeExceeded

        val resId:Int = if(willRespond) acquireResId(canWaitFor) else 0
        if(resId < 0) return resId

        try {
            sendData {
                val dataSecSize = len + sizeOfResId
                if (dataSecSize < 256) {
                    outStream!!.write(RqByteSignal.toInt())
                    outStream!!.write(dataSecSize)
                }
                else {
                    outStream!!.write(RqShortSignal.toInt())
                    outStream!!.writeShort(dataSecSize)
                }
                outStream!!.write(bf, off, len)
                writeResId(outStream!!, resId)
            }
        }
        catch (e: IOException) {
            releaseResId(resId)
            return ErrorByStreamClosed
        }
        return resId
    }
    fun sendResponse(resId:Int, bf:ByteArray, off:Int=0, len:Int=bf.size): Int {
        if(!isBridgeAlive) return ErrorByBridgeNotAlive
        else if(len >= sizeOfPacket) return ErrorByDataSizeExceeded
        else if(resId < 0 || resId > limitOfResId) return ErrorByInvalidId

        try {
            sendData {
                val dataSecSize = len + sizeOfResId
                if (dataSecSize < 256) {
                    outStream!!.write(ResByteSignal.toInt())
                    outStream!!.write(dataSecSize)
                }
                else {
                    outStream!!.write(ResShortSignal.toInt())
                    outStream!!.writeShort(dataSecSize)
                }
                outStream!!.write(bf, off, len)
                writeResId(outStream!!, resId)
            }
        }
        catch (e: IOException) {
            releaseResId(resId)
            return ErrorByStreamClosed
        }
        return resId
    }

    fun retrieveRequest(reqId:Int, canWaitFor:Long=Long.MAX_VALUE):ByteArray? {
        if(reqId <= 0 || reqId > limitOfReqId) return null
        requestMapLock.withLock {
            if(!requestMap.containsKey(reqId)) return null
            else if(canWaitFor > 0L) requestMapCondition.awaitTill(canWaitFor) {
                isBridgeAlive && requestMap.containsKey(reqId) &&
                        requestMap[reqId]==null
            }
            return releaseReqId(reqId)
        }
    }
    fun retrieveResponse(resId:Int, canWaitFor:Long=Long.MAX_VALUE):ByteArray? {
        if(resId <= 0 || resId > limitOfResId) return null
        responseMapLock.withLock {
            if(!responseMap.containsKey(resId)) return null
            else if(canWaitFor > 0L) responseMapCondition.awaitTill(canWaitFor) {
                isBridgeAlive && responseMap.containsKey(resId) &&
                        responseMap[resId]==null
            }
            return releaseResId(resId)
        }
    }

    fun joinResponses() = responseMapLock.withLock {
        while (isBridgeAlive) {
            if (responseMap.isEmpty())
                return
            responseMapCondition.await()
        }
    }

    private fun clearRequests() = requestMapLock.withLock {
        requestMap.clear()
        requestMapCondition.signalAll()
    }
    private fun clearResponses() = responseMapLock.withLock {
        responseMap.clear()
        responseMapCondition.signalAll()
    }
}