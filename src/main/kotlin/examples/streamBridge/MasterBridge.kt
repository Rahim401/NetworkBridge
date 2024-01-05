package examples.streamBridge

import bridgeCore.DuplexStream
import bridgeCore.SockStream.Companion.toSockStream
import bridgeCore.StreamBridge
import examples.appPort
import examples.listenConnectTimeout
import examples.BridgeState
import examples.InitializeCode
import examples.streamBridge.MasterBridge.Companion.streamManagerCLI
import examples.streamBridge.MasterBridge.Companion.stressTestStreams
import writeData
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

class MasterBridge: StreamBridge() {
    var currentState = BridgeState.Idle
        private set

    private var mainSocket: Socket? = null

    override var inStream: InputStream? = null
    override var outStream: OutputStream? = null

    val isConnected:Boolean
        get() = currentState == BridgeState.Connected
    private val workerAddr: SocketAddress?
        get() = mainSocket?.remoteSocketAddress

    override fun setTimeout(timeout: Long) {
        mainSocket?.soTimeout = timeout.toInt()
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

                sendData { writeData(InitializeCode, 0.toByte(), confBeatInter.toShort()) }
                val connRes = inStream!!.readNBytes(2)
                if(connRes.size == 2 && connRes[0] == InitializeCode && connRes[1] == 1.toByte()){
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
    override fun makeStream(): DuplexStream? {
        if(currentState != BridgeState.Connected) return null

        var connSock: Socket? = null
        try {
            connSock = Socket()
            connSock.soTimeout = confMakeStmTimeout.toInt()
            connSock.connect(InetSocketAddress(mainSocket!!.inetAddress, appPort))
            if(connSock.isConnected)
                return connSock.toSockStream()
        }
        catch (e:SocketException){ e.printStackTrace() }
        catch (e:SocketTimeoutException){ e.printStackTrace() }
        connSock?.close()
        return null
    }
    fun disconnect(){
        if(currentState == BridgeState.Connected || currentState == BridgeState.Connecting) {
            currentState = BridgeState.Disconnecting
            stopBridgeLooper()
        }
    }

    companion object {
        fun streamManagerCLI(brg: StreamBridge, isConnected:()->Boolean) {
            try {
                println("\nStream Manager CLI, available actions are:")
                println("   create : Which makes a Socket Connection and shares its sockId")
                println("   write <sockId> : Writes into OutStream of Socket(sockId), Press ENTER twice to exit writing")
                println("   read <sockId> : Reads from InStream of Socket(sockId), Press ENTER to exit reading")
                println("   exit : To exit the program")
                println()

                val screenReader = Scanner(System.`in`)
                while (isConnected()) {
                    print("What to do: ")
                    val command = screenReader.nextLine().split(" ")
                    when (command.getOrNull(0)?.lowercase()) {
                        "create" -> {
                            val sockId = brg.makeAndConnStream()
                            if (sockId < 0) println("Error on creation occurred $sockId\n")
                            else println("New Sock($sockId) created!\n")
                        }
                        "write" -> {
                            val onSock = command.getOrNull(1)?.toInt()
                            if (onSock == null) println("Invalid SockId!\n")
                            else if (onSock >= 0 && !brg.isOutStreamAvailable(onSock))
                                println("OutStream of Sock($onSock) is Unavailable!\n")
                            else {
                                var noOfLastLnBreaks = 0
                                val consoleInStream = System.`in`
                                brg.withOutStream(onSock) { outIdx, outStream ->
                                    if (outStream == null) println("Error in acquiring OutStream!\n")
                                    else {
                                        println("Writing on Stream of the Sock($outIdx)")
                                        while (isConnected()) {
                                            val charRead = consoleInStream.read()
                                            if (charRead == 10) {
                                                noOfLastLnBreaks++
                                                if (noOfLastLnBreaks >= 2)
                                                    break
                                            }
                                            else if(charRead != 13)
                                                noOfLastLnBreaks = 0
                                            outStream.write(charRead)
                                        }
                                    }
                                }
                            }
                        }
                        "read" -> {
                            val onSock = command.getOrNull(1)?.toInt()
                            if (onSock == null) println("Invalid SockId!\n")
                            else if (onSock >= 0 && !brg.isInStreamAvailable(onSock))
                                println("InStream of Sock($onSock) is Unavailable!\n")
                            else {
                                val consoleInStream = System.`in`
                                brg.withInStream(onSock) { inIdx, inStream ->
                                    if (inStream == null) println("Error in acquiring InStream!\n")
                                    else {
                                        println("Reading from Stream of the Sock($inIdx)")
                                        brg.setStreamTimeout(inIdx, 100)
                                        recvLoop@ while (isConnected()) {
                                            try {
                                                val charRecv = inStream.read()
                                                if (charRecv >= 0) print(charRecv.toChar())
                                            }
                                            catch (e: SocketTimeoutException) { }
                                            while (consoleInStream.available() > 0) {
                                                if (consoleInStream.read() == 10)
                                                    break@recvLoop
                                            }
                                        }
                                        brg.setStreamTimeout(onSock, 0)
                                    }
                                }
                            }
                        }
                        "exit" -> {
                            println("Exiting Console!\n")
                            break
                        }
                        else -> println("Enter a valid command yo!\n")
                    }
                }

            }
            catch (e:SocketException) { println("An Error occurred!\n") }
        }
        fun stressTestStreams(brg: StreamBridge, isConnected:()->Boolean, testFor:Int=10000, streamTimeout:Int=0) {
            while (!isConnected()) Thread.sleep(10)
            val threadList = arrayListOf<Thread>()
            repeat(testFor) {
                threadList.add(
                    thread {
                        val stmId = if(it < 1000) -1 else it%1000
                        brg.withInStream(stmId, waitToCreate=true) { inpIdx, inStream ->
                            if(inStream==null) println("${System.currentTimeMillis()} ${Thread.currentThread().name} $it: Can't create for InStream with error $inpIdx")
                            else {
                                brg.withOutStream(inpIdx, waitToCreate=true) { outIdx, outStream ->
                                    if(outStream==null) println("${System.currentTimeMillis()} ${Thread.currentThread().name} $inpIdx $outIdx: Can't create for OutStream with error $outIdx")
                                    else {
                                        brg.setStreamTimeout(inpIdx, streamTimeout)
                                        var lastReplay = -1
                                        try {
                                            repeat(10) { pingCount ->
                                                outStream.write(pingCount)
                                                Thread.sleep(100)
                                                lastReplay = inStream.read()
                                            }
                                            println("${System.currentTimeMillis()} ${Thread.currentThread().name} $inpIdx $outIdx: Completely Done!")
                                        }
                                        catch (e: InterruptedIOException) {
                                            println("${System.currentTimeMillis()} ${Thread.currentThread().name} $inpIdx $outIdx: Timeout with $lastReplay!")
                                        }
                                        brg.setStreamTimeout(inpIdx, 0)
                                    }
                                }
                            }
                        }
                    }
                )
            }
            for(i in 0 until threadList.size)
                threadList.removeAt(0).join()
        }
    }
}



fun main() {
    val bridge = MasterBridge()
    bridge.connectTo("localhost")
    while (!bridge.isConnected) Thread.sleep(10)
    streamManagerCLI(bridge, bridge::isConnected)
//    stressTestStreams(bridge, bridge::isConnected)
    bridge.disconnect()
}