package examples.basicBridge

import bridgeCore.Bridge
import bridgeCore.appPort
import bridgeCore.listenConnectTimeout
import examples.BridgeState
import examples.InitializeCode
import getLong
import getShort
import writeData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*

class WorkerBridge: Bridge() {
    var currentState = BridgeState.Idle
        private set

    private val skLaneMaker = ServerSocket(appPort)
    private var mainSkLane: Socket? = null
    override var inLane: DataInputStream? = null
    override var outLane: DataOutputStream? = null

    val isConnected:Boolean
        get() = currentState==BridgeState.Connected
    private val masterAddr: SocketAddress?
        get() = mainSkLane?.remoteSocketAddress
    private var deviceTimeDiff = 0L


    override fun setInStreamTimeout(timeout: Long) {
        mainSkLane?.soTimeout = timeout.toInt()
    }
    override fun getSignalSize(signal: Byte): Int = -1
    override fun handleSignal(signal: Byte, bf: ByteArray, size: Int) {}

    fun startListening() {
        if(currentState != BridgeState.Idle) return

        currentState = BridgeState.StartingListen
        try {
            skLaneMaker.soTimeout = listenConnectTimeout
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
            mainSkLane = skLaneMaker.accept()
            currentState = BridgeState.Connecting
            if (mainSkLane!!.isConnected) {
                inLane = DataInputStream(mainSkLane!!.getInputStream())
                outLane = DataOutputStream(mainSkLane!!.getOutputStream())

                val connReq = inLane!!.readNBytes(12)
                val requestAt = System.currentTimeMillis()
                if (connReq.size == 12 && connReq[0] == InitializeCode && connReq[1] == 0.toByte()) {
                    updateConfInters(connReq.getShort(2).toLong())
                    deviceTimeDiff = requestAt - connReq.getLong(4)

                    sendData { outLane!!.writeData(InitializeCode, 1.toByte(), System.currentTimeMillis()) }
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

        mainSkLane?.close(); mainSkLane = null; inLane = null; outLane = null
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