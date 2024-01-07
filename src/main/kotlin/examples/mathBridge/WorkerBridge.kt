/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package examples.mathBridge

import bridgeCore.RequestBridge
import examples.appPort
import examples.listenConnectTimeout
import examples.BridgeState
import examples.InitializeCode
import getInt
import getLong
import getSString
import getShort
import getUShort
import putDouble
import putSString
import writeData
import writeShort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.net.*
import kotlin.concurrent.thread

class WorkerBridge: RequestBridge() {
    var currentState = BridgeState.Idle
        private set

    private val socketMaker = ServerSocket(appPort)
    private var mainSocket: Socket? = null
    override var inStream: InputStream? = null
    override var outStream: OutputStream? = null

    val isConnected:Boolean
        get() = currentState==BridgeState.Connected
    private val masterAddr: SocketAddress?
        get() = mainSocket?.remoteSocketAddress
    private var deviceTimeDiff = 0L

    override val limitOfResId: Int = 1024
    override val limitOfReqId: Int = limitOfResId*2
    override val sizeOfResId: Int = 2
    override fun writeResId(outStm: OutputStream, resId: Int) = outStm.writeShort(resId)
    override fun readResId(bf: ByteArray, pos: Int): Int = bf.getUShort(pos)

    override fun setTimeout(timeout: Long) {
        mainSocket?.soTimeout = timeout.toInt()
    }
    override fun onRequest(resId: Int, reqId: Int, bf: ByteArray, size: Int): Boolean {
        if(resId < 0){
            println("RequestId($reqId): Can't handle due to $resId")
            return false
        }

        val resDelay = bf.getInt().toLong()
        if(resDelay == 0L){
            handleMathRequest(resId, bf, 4)
            return false
        }
        else thread {
            val causeOfError = if(reqId >= 0) {
                Thread.sleep(resDelay)
                val requestData = retrieveRequest(reqId)
                if(requestData != null) {
                    handleMathRequest(resId, requestData)
                    return@thread
                } else "Couldn't retrieve with error $reqId"
            } else "No Space to Cache with error $reqId"

            val responseBuffer = ByteArray(100)
            responseBuffer[0] = -2
            val errorLen = responseBuffer.putSString(1, causeOfError)
            sendResponse(resId, responseBuffer, 0, 1+errorLen)
            println("ResponseId($resId): $causeOfError")
        }
        return true
    }
    override fun onResponse(resId: Int, bf: ByteArray, size: Int): Boolean = false
    private fun handleMathRequest(resId: Int, bf: ByteArray, off: Int = 4){
        val expression = bf.getSString(off)
        val responseBuffer = ByteArray(100)
        try {
            val result = eval(expression)
            responseBuffer[0] = 0
            responseBuffer.putDouble(1,result)
            sendResponse(resId, responseBuffer,0,9)
            println("ResponseId($resId): Evaluated $expression with result $result")
        }
        catch (e: Exception){
            responseBuffer[0] = -1
            val errorLen = responseBuffer.putSString(1, (e.message ?: "Unknown Error"))
            sendResponse(resId, responseBuffer, 0, 1 + errorLen)
            println("ResponseId($resId): $expression got Error ${e.message}")
        }
    }

    fun startListening() {
        if(currentState != BridgeState.Idle) return

        currentState = BridgeState.StartingListen
        try {
            socketMaker.soTimeout = listenConnectTimeout
            currentState = BridgeState.Listening
            println("Started Listening")
            while (currentState == BridgeState.Listening)
                acceptConnection()
        }
        catch (e: SocketException){ e.printStackTrace() }
        currentState = BridgeState.Idle
    }
    private fun acceptConnection() {
        if(currentState!=BridgeState.Listening) return
        try {
            mainSocket = socketMaker.accept()
            currentState = BridgeState.Connecting
            if (mainSocket!!.isConnected) {
                inStream = DataInputStream(mainSocket!!.getInputStream())
                outStream = DataOutputStream(mainSocket!!.getOutputStream())

                val connReq = inStream!!.readNBytes(12)
                val requestAt = System.currentTimeMillis()
                if (connReq.size == 12 && connReq[0] == InitializeCode && connReq[1] == 0.toByte()) {
                    updateConfInters(connReq.getShort(2).toLong())
                    deviceTimeDiff = requestAt - connReq.getLong(4)

                    sendData { writeData(InitializeCode, 1.toByte(), System.currentTimeMillis()) }
                    if(currentState == BridgeState.Connecting) {
                        currentState = BridgeState.Connected
                        println("\nConnected to Master($masterAddr)")
                        val becauseOf = startBridgeLooper()
                        println("Disconnected Because Of $becauseOf")
                    }
                }
            }
        }
        catch (e: SocketException){ e.printStackTrace() }
        catch (e: SocketTimeoutException) {}

        mainSocket?.close(); mainSocket = null; inStream = null; outStream = null
        if(currentState != BridgeState.StopingListen) currentState = BridgeState.Listening
    }
    fun disconnect(){
        if(currentState == BridgeState.Connected || currentState == BridgeState.Connecting) {
            currentState = BridgeState.Disconnecting
            stopBridgeLooper()
        }
    }
    fun stopListening(){
        if(currentState != BridgeState.Idle) {
            currentState = BridgeState.StopingListen
            stopBridgeLooper()
        }
    }
}

fun main() {
    val bridge = WorkerBridge()
    bridge.startListening()
}