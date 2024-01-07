/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package examples.basicBridge

import bridgeCore.Bridge
import examples.appPort
import examples.listenConnectTimeout
import examples.BridgeState
import examples.InitializeCode
import getLong
import getShort
import writeData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*

class WorkerBridge: Bridge() {
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


    override fun setTimeout(timeout: Long) {
        mainSocket?.soTimeout = timeout.toInt()
    }
    override fun getSignalSize(signal: Byte): Int = -1
    override fun handleSignal(signal: Byte, bf: ByteArray, size: Int) {}

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

    private fun disconnect(){
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
    val wb = WorkerBridge()
    wb.startListening()
}