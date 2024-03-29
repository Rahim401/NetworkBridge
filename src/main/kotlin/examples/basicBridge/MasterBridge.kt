/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package examples.basicBridge

import bridgeCore.Bridge
import examples.BridgeState
import examples.InitializeCode
import examples.appPort
import examples.listenConnectTimeout
import getLong
import writeData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * This is a basic Master-Slave implementation of Bridge with Tcp Streams
 */
class MasterBridge: Bridge() {
    var currentState = BridgeState.Idle
        private set

    private var mainSocket: Socket? = null
    override var inStream: InputStream? = null
    override var outStream: OutputStream? = null

    val isConnected:Boolean
        get() = currentState==BridgeState.Connected
    private val workerAddr: SocketAddress?
        get() = mainSocket?.remoteSocketAddress
    private var deviceTimeDiff = 0L


    override fun setTimeout(timeout: Long) {
        mainSocket?.soTimeout = timeout.toInt()
    }
    override fun getSignalSize(signal: Byte): Int = -1
    override fun handleSignal(signal: Byte, bf: ByteArray, size: Int) {}

    fun connectTo(addr: String) {
        if(currentState != BridgeState.Idle) return

        currentState = BridgeState.Connecting
        try {
            mainSocket = Socket()
            mainSocket!!.soTimeout = listenConnectTimeout
            mainSocket!!.connect(InetSocketAddress(addr,appPort))

            if(mainSocket!!.isConnected){
                inStream = DataInputStream(mainSocket!!.getInputStream())
                outStream = DataOutputStream(mainSocket!!.getOutputStream())

                sendData { writeData(InitializeCode, 0.toByte(), confBeatInter.toShort(), System.currentTimeMillis()) }
                val connRes = inStream!!.readNBytes(10)
                val responseAt = System.currentTimeMillis()
                if(connRes.size == 10 && connRes[0] == InitializeCode && connRes[1] == 1.toByte()){
                    deviceTimeDiff = responseAt - connRes.getLong(2)

                    thread(name="LooperThread"){
                        if(currentState == BridgeState.Connecting) {
                            currentState = BridgeState.Connected
                            println("\nConnected to Worker($workerAddr)")
                            val becauseOf = startBridgeLooper()
                            println("Disconnected Because Of $becauseOf")
                        }
                        mainSocket?.close(); mainSocket = null; inStream = null; outStream = null
                        currentState = BridgeState.Idle
                    }
                    return
                }
            }
        }
        catch (e: ConnectException) { throw TimeoutException("No worker on Ip $addr").initCause(e) }
        catch (e: SocketTimeoutException) { e.printStackTrace(); throw TimeoutException("No worker on Ip $addr") }
        catch (e: SocketException){ e.printStackTrace() }
        mainSocket?.close(); mainSocket = null; inStream = null; outStream = null
        currentState = BridgeState.Idle
    }

    fun disconnect(){
        if(currentState == BridgeState.Connected || currentState == BridgeState.Connecting) {
            currentState = BridgeState.Disconnecting
            stopBridgeLooper()
        }
    }
}

fun main() {
    val mb = MasterBridge()
    mb.connectTo("localhost")
    while (!mb.isConnected) Thread.sleep(1)
    Thread.sleep(10000)
    mb.disconnect()
}