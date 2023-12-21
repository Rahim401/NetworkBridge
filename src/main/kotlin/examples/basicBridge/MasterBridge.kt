package examples.basicBridge

import bridgeCore.Bridge
import bridgeCore.appPort
import bridgeCore.listenConnectTimeout
import examples.BridgeState
import examples.InitializeCode
import getLong
import writeData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

class MasterBridge: Bridge() {
    var currentState = BridgeState.Idle
        private set

    private var mainSkLane: Socket? = null
    override var inLane: DataInputStream? = null
    override var outLane: DataOutputStream? = null

    val isConnected:Boolean
        get() = currentState==BridgeState.Connected
    private val workerAddr: SocketAddress?
        get() = mainSkLane?.remoteSocketAddress
    private var deviceTimeDiff = 0L


    override fun setInStreamTimeout(timeout: Long) {
        mainSkLane?.soTimeout = timeout.toInt()
    }
    override fun getSignalSize(signal: Byte): Int = -1
    override fun handleSignal(signal: Byte, bf: ByteArray, size: Int) {}

    fun connectTo(addr: String) {
        if(currentState != BridgeState.Idle) return

        currentState = BridgeState.Connecting
        try {
            mainSkLane = Socket()
            mainSkLane!!.soTimeout = listenConnectTimeout
            mainSkLane!!.connect(InetSocketAddress(addr,appPort))

            if(mainSkLane!!.isConnected){
                inLane = DataInputStream(mainSkLane!!.getInputStream())
                outLane = DataOutputStream(mainSkLane!!.getOutputStream())

                sendData { outLane!!.writeData(InitializeCode, 0.toByte(), confBeatInter.toShort(), System.currentTimeMillis()) }
                val connRes = inLane!!.readNBytes(10)
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
                        mainSkLane?.close(); mainSkLane = null; inLane = null; outLane = null
                        currentState = BridgeState.Idle
                    }
                    return
                }
            }
        }
        catch (e: ConnectException) { throw TimeoutException("No worker on Ip $addr").initCause(e) }
        catch (e: SocketTimeoutException) { e.printStackTrace(); throw TimeoutException("No worker on Ip $addr") }
        catch (e: SocketException){ e.printStackTrace() }
        mainSkLane?.close(); mainSkLane = null; inLane = null; outLane = null
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