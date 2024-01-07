/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package bridgeCore

import doAwaitTill
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

    fun makeAndConnStream(willSignal:Boolean=true, canWaitFor: Long=Long.MAX_VALUE): Int {
        if(!isBridgeAlive) return ErrorByBridgeNotAlive
        makeStmLock.withLock {
            makeStmCondition.doAwaitTill(canWaitFor) {
                if (stmList.size < limitForStreams) {
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
                return@doAwaitTill true
            }
            return ErrorByLimitReached
        }
    }
    fun isInStreamAvailable(stmId:Int) = 0 <= stmId && stmId < stmList.size && stmList[stmId].isInStreamAvailable
    fun isOutStreamAvailable(stmId:Int) = 0 <= stmId && stmId < stmList.size && stmList[stmId].isOutStreamAvailable
    fun setStreamTimeout(stmId: Int, timeout: Int) = stmList.getOrNull(stmId)?.streamObject?.setTimeout(timeout)

    fun acquireInStream(stmId:Int=-1, canCreate: Boolean=true, waitToCreate:Boolean=false, canWaitFor: Long=Long.MAX_VALUE): Pair<Int, InputStream?> {
        if(isBridgeAlive) makeStmLock.withLock {
            var resCode = ErrorByStreamUnavailable
            makeStmCondition.doAwaitTill(canWaitFor) { timeLeft ->
                resCode = if(isBridgeAlive) {
                    if(stmId < 0) {
                        stmList.forEachIndexed { idx, rStm ->
                            if (rStm.isInStreamAvailable) {
                                resCode = idx
                                return@doAwaitTill false
                            }
                        }
                        if (canCreate) makeAndConnStream(false, timeLeft)
                        else return@doAwaitTill true
                    }
                    else if(stmId < stmList.size) {
                        if (isInStreamAvailable(stmId)) stmId
                        else return@doAwaitTill true
                    }
                    else if(stmId < limitForStreams) {
                        if (waitToCreate) return@doAwaitTill true
                        else ErrorByStreamUnavailable
                    }
                    else ErrorByInvalidId
                }
                else ErrorByBridgeNotAlive
                return@doAwaitTill false
            }
            return Pair(resCode, stmList.getOrNull(resCode)?.acquireInStream())
        }
        return Pair(ErrorByBridgeNotAlive, null)
    }
    fun releaseInStream(stmId:Int=-1) {
        if(0 <= stmId && stmId < stmList.size) makeStmLock.withLock {
            stmList.getOrNull(stmId)?.releaseInStream()
            makeStmCondition.signalAll()
        }
    }
    inline fun withInStream(
        stmId:Int=-1, canCreate: Boolean=true, waitToCreate: Boolean=false, canWaitFor: Long=Long.MAX_VALUE,
        block:(inpIdx:Int, inStream: InputStream?)->Unit
    ){
        val (inpIdx,inpStream) = acquireInStream(stmId, canCreate, waitToCreate, canWaitFor)
        block(inpIdx,inpStream)
        releaseInStream(inpIdx)
    }

    fun acquireOutStream(stmId:Int=-1, canCreate: Boolean=true, waitToCreate:Boolean=false, canWaitFor: Long=Long.MAX_VALUE): Pair<Int, OutputStream?> {
        if(isBridgeAlive) makeStmLock.withLock {
            var resCode = ErrorByStreamUnavailable
            makeStmCondition.doAwaitTill(canWaitFor) { timeLeft ->
                resCode = if(isBridgeAlive) {
                    if(stmId < 0) {
                        stmList.forEachIndexed { idx, rStm ->
                            if (rStm.isOutStreamAvailable) {
                                resCode = idx
                                return@doAwaitTill false
                            }
                        }
                        if (canCreate)  makeAndConnStream(false, timeLeft)
                        else return@doAwaitTill true
                    }
                    else if(stmId < stmList.size) {
                        if (isOutStreamAvailable(stmId)) stmId
                        else return@doAwaitTill true
                    }
                    else if(stmId < limitForStreams) {
                        if (waitToCreate) return@doAwaitTill true
                        else ErrorByStreamUnavailable
                    }
                    else ErrorByInvalidId
                }
                else ErrorByBridgeNotAlive
                return@doAwaitTill false
            }
            return Pair(resCode, stmList.getOrNull(resCode)?.acquireOutStream())
        }
        return Pair(ErrorByBridgeNotAlive, null)
    }
    fun releaseOutStream(stmId:Int=-1) {
        if(0 <= stmId && stmId < stmList.size) makeStmLock.withLock {
            stmList.getOrNull(stmId)?.releaseOutStream()
            makeStmCondition.signalAll()
        }
    }
    inline fun withOutStream(
        stmId:Int=-1, canCreate: Boolean=true, waitToCreate: Boolean=false, canWaitFor: Long=Long.MAX_VALUE,
        block:(outIdx:Int, outStream: OutputStream?)->Unit
    ){
        val (outIdx,outStream) = acquireOutStream(stmId, canCreate, waitToCreate, canWaitFor)
        block(outIdx,outStream)
        releaseOutStream(outIdx)
    }

    private fun closeAllStreams() = makeStmLock.withLock {
        stmList.forEach { it.streamObject.releaseStream() }
        stmList.clear()
    }
}