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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * This is also an abstract class on top of `Bridge` which provides thread safe way
 * to Create and Manage Multiple Streams in a efficient and reusable way
 * - **Stream Id** is the id used to acquire in/output stream of a specific `DuplexStream`
 */
abstract class StreamBridge: Bridge() {
    var confMakeStmTimeout = confBeatInterBy4 / 2
    var confConnStmTimeout = confMakeStmTimeout / 5

    private val makeStmLock = ReentrantLock()
    private val makeStmCondition = makeStmLock.newCondition()
    private var stmList:ArrayList<RentableStream> = ArrayList()

    override fun updateConfInters(inter:Long){
        super.updateConfInters(inter)
        confMakeStmTimeout = confBeatInterBy4 / 2
        confConnStmTimeout = confMakeStmTimeout / 5
    }

    /**
     * No of max streams that can be created
     */
    protected open val limitForStreams = 1024
    /**
     * A method which need to create and return a BiDirectional Channel
     */
    abstract fun makeStream(): DuplexStream?

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

    /**
     * A method which create and validate the streams on demand
     * @param willSignal true if need to notify all other threads waiting for a stream
     * @param canWaitFor amount of time it can wait for a stream creation in milliseconds
     */
    fun makeAndConnStream(willSignal:Boolean=true, canWaitFor: Long=Long.MAX_VALUE): Int {
        if(!isBridgeAlive) return ErrorByBridgeNotAlive
        else if(stmList.size >= limitForStreams) return ErrorByLimitReached
        makeStmLock.withLock {
            makeStmCondition.doAwaitTill(canWaitFor) {
                if(!isBridgeAlive) return ErrorByBridgeNotAlive
                else if(stmList.size >= limitForStreams) return ErrorByLimitReached

                var causeOfError: Int
                var stmObj: DuplexStream? = null
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
            return ErrorByUnexpectedException
        }
    }
    fun isInStreamAvailable(stmId:Int) = 0 <= stmId && stmId < stmList.size && stmList[stmId].isInStreamAvailable
    fun isOutStreamAvailable(stmId:Int) = 0 <= stmId && stmId < stmList.size && stmList[stmId].isOutStreamAvailable
    fun setStreamTimeout(stmId: Int, timeout: Int) = stmList.getOrNull(stmId)?.streamObject?.setTimeout(timeout)

    /**
     * Acquires and returns a InputStream, remember once the usage of stream finished you need to
     * release it with `releaseInStream`, So that it can be reused
     * @param stmId id of the stream needed to be acquired, -1 if need to acquire any available stream
     * @param canCreate weather can it create new Stream if all existing streams are busy
     * @param waitToCreate weather can it wait for creation if stream of `stmId` currently not exists
     * @param canWaitFor amount of time it can wait for the creation of the Stream
     */
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
    /** Release an InputStream */
    fun releaseInStream(stmId:Int=-1) {
        if(0 <= stmId && stmId < stmList.size) makeStmLock.withLock {
            stmList.getOrNull(stmId)?.releaseInStream()
            makeStmCondition.signalAll()
        }
    }
    /** Execute a code with InputStream acquired */
    inline fun withInStream(
        stmId:Int=-1, canCreate: Boolean=true, waitToCreate: Boolean=false, canWaitFor: Long=Long.MAX_VALUE,
        block:(inpIdx:Int, inStream: InputStream?)->Unit
    ){
        val (inpIdx,inpStream) = acquireInStream(stmId, canCreate, waitToCreate, canWaitFor)
        block(inpIdx,inpStream)
        releaseInStream(inpIdx)
    }


    /**
     * Acquires and returns a OutStream, remember once the usage of stream finished you need to
     * release it with `releaseOutStream`, So that it can be reused
     * @param stmId id of the stream needed to be acquired, -1 if need to acquire any available stream
     * @param canCreate weather can it create new Stream if all existing streams are busy
     * @param waitToCreate weather can it wait for creation if stream of `stmId` currently not exists
     * @param canWaitFor amount of time it can wait for the creation of the Stream
     */
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
    /** Release an OutputStream */
    fun releaseOutStream(stmId:Int=-1) {
        if(0 <= stmId && stmId < stmList.size) makeStmLock.withLock {
            stmList.getOrNull(stmId)?.releaseOutStream()
            makeStmCondition.signalAll()
        }
    }
    /** Execute a code with OutputStream acquired */
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