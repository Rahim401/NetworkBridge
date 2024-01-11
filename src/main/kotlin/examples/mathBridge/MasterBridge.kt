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
import getDouble
import getLong
import getSString
import getUShort
import putInt
import putSString
import writeData
import writeShort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * This is an Server-Client implementation of Request bridge, to compute maths. Here this is the Server,
 * which will compute math expressions sent by ClientBridge and sends the result back
 */
class MasterBridge: RequestBridge() {
    var currentState = BridgeState.Idle
        private set

    private var mainSocket: Socket? = null
    override var inStream: InputStream? = null
    override var outStream: OutputStream? = null

    val isConnected:Boolean
        get() = currentState == BridgeState.Connected
    private val workerAddr: SocketAddress?
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
    override fun onRequest(resId: Int, reqId: Int, bf: ByteArray, size: Int): Boolean = false
    override fun onResponse(resId: Int, bf: ByteArray, size: Int): Boolean {
        return doOnResponse?.invoke(resId, bf, size) ?: false
    }
    private fun sendMathRequest(expression: String, withDelay:Int= 0):Int {
        val requestBuffer = ByteArray(100)
        requestBuffer.putInt(0, withDelay)
        val expLen = requestBuffer.putSString(4, expression)
        return sendRequest(requestBuffer, 0, 4 + expLen, willRespond = true)
    }
    private fun printMathResponse(resId: Int, bf: ByteArray) {
        val isEvaluated = bf[0] == 0.toByte()

        val message =  if(isEvaluated) "Result ${bf.getDouble(1)}"
        else "Error ${bf.getSString(1)}"
        println("\r\tReceived $message for ResponseId($resId)!")
    }
    private var doOnResponse:((resId: Int, bf: ByteArray, size: Int)->Boolean)? = { resId, bf, _ ->
        printMathResponse(resId, bf)
        false
    }

    fun startMathCli() {
        if(isConnected) {
            doOnResponse = { resId, bf, _ ->
                printMathResponse(resId, bf)
                print("\rExpression: ")
                false
            }

            println("""
            
            Math CLI, Evaluate Mathematical expressions on Peer:
                Syntax: expression [: delay in sec]
                Expression can have sin, cos, tan and ^ with decimals
                
            """.trimIndent()
            )
            try {
                val screenReader = Scanner(System.`in`)
                while (isConnected) {
                    print("Expression: ")
                    val command = screenReader.nextLine().split(":")
                    val exp = command.getOrNull(0)?.trim()
                    if (exp == null || exp.isEmpty())
                        println("\tInvalid expression!")
                    else {
                        if (exp == "exit") break
                        if (exp == "disconnect"){
                            disconnect()
                            break
                        }
                        val delay = (command.getOrNull(1)
                            ?.trim()?.toIntOrNull()?.times(1000)) ?: 0
                        val resId = sendMathRequest(exp, delay)
                        println("\tSent with ResponseId($resId)!")
                    }
                }
            }
            catch (e:SocketException) { println("An Error occurred!\n") }
        }
        doOnResponse = { resId, bf, _ ->
            printMathResponse(resId, bf)
            false
        }
    }
    fun startStressTest(
        noOfRequests:Int = 10000,
        genRequest:(Int)->Pair<String,Int> = { Pair("$it",it*10) }
    ) {
        if(!isConnected) return
        doOnResponse = { resId, bf, _ ->
            printMathResponse(resId, bf)
            false
        }

        println("Gonna StressTest MathServer with $noOfRequests no of Requests")
        for(i in 0 until noOfRequests) {
            if(!isConnected) break
            val (expr,delay) = genRequest(i)
            val resId = sendMathRequest(expr, delay)
            println("\tSent $expr with ResponseId($resId)!")
        }
    }

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

    fun waitForAllResults() = joinResponses()

    fun disconnect(){
        if(currentState == BridgeState.Connected || currentState == BridgeState.Connecting) {
            currentState = BridgeState.Disconnecting
            stopBridgeLooper()
        }
    }
}

fun main() {
    val bridge = MasterBridge()
    bridge.connectTo("localhost")
    while (!bridge.isConnected) Thread.sleep(100)

    bridge.startMathCli()
//    bridge.startStressTest(10000) {
//        return@startStressTest Pair("$it*5",1000)
//    }
    bridge.waitForAllResults()
    bridge.disconnect()
}