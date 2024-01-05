package examples.streamBridge

import bridgeCore.DuplexStream
import bridgeCore.RentableStream
import bridgeCore.SockStream.Companion.toSockStream
import bridgeCore.StreamBridge
import examples.appPort
import examples.listenConnectTimeout
import examples.BridgeState
import examples.InitializeCode
import examples.streamBridge.MasterBridge.Companion.streamManagerCLI
import examples.streamBridge.MasterBridge.Companion.stressTestStreams
import getShort
import writeData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import kotlin.concurrent.thread

class WorkerBridge: StreamBridge() {
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

    override fun setTimeout(timeout: Long) {
        mainSocket?.soTimeout = timeout.toInt()
    }
    override fun makeStream(): DuplexStream? {
        if(currentState != BridgeState.Connected) return null
        var waitingSock: Socket? = null
        socketMaker.soTimeout = confMakeStmTimeout.toInt()
        val listenTill = System.currentTimeMillis() + confMakeStmTimeout
        try {
            while (System.currentTimeMillis() < listenTill) {
                waitingSock = socketMaker.accept()
                if(waitingSock!=null && waitingSock.isConnected){
                    waitingSock.soTimeout = confConnStmTimeout.toInt()
                    return waitingSock.toSockStream()
                }
                waitingSock?.close()
            }
        }
        catch (e:SocketException){ e.printStackTrace() }
        catch (e:SocketTimeoutException){ e.printStackTrace() }
        waitingSock?.close()
        return null
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

                val connReq = inStream!!.readNBytes(4)
                if (connReq.size == 4 && connReq[0] == InitializeCode && connReq[1] == 0.toByte()) {
                    updateConfInters(connReq.getShort(2).toLong())

                    sendData { writeData(InitializeCode, 1.toByte()) }
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
    thread {
        repeat(20) {
            while (!bridge.isConnected) Thread.sleep(1)
            streamManagerCLI(bridge, bridge::isConnected)
//            stressTestStreams(bridge, bridge::isConnected)
            while (bridge.isConnected) Thread.sleep(100)
        }
    }
    bridge.startListening()
}