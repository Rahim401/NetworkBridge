package examples.chatBridge

import examples.chatBridge.ChatBridge.Companion.chatReceiver
import examples.chatBridge.ChatBridge.Companion.chatSender
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
import kotlin.concurrent.thread

class WorkerBridge: ChatBridge() {
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
    val wb = WorkerBridge()
    thread {
        repeat(10) {
            while (!wb.isConnected) Thread.sleep(100)
            thread { chatReceiver(wb, "Master"); }
            chatSender(wb); wb.disconnect()
            while (wb.isConnected) Thread.sleep(100)
        }
    }
    wb.startListening()
}