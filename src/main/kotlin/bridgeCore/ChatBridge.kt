package bridgeCore

import getSString
import putSString
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock

abstract class ChatBridge: RequestBridge() {
    private val messageLock = ReentrantLock()
    private val messageCondition = messageLock.newCondition()
    private val messageIdList = ArrayList<Int>()
    override fun onRequest(reqId: Int, bf: ByteArray, size: Int): Boolean {
        messageLock.withLock {
            messageIdList.add(reqId)
            messageCondition.signalAll()
        }
        return true
    }
    fun getAvailableMessage():Pair<Int,String?> {
        var messageId = 0
        messageLock.withLock {
            while (isBridgeAlive) {
                if (messageIdList.size > 0) {
                    messageId = messageIdList.removeAt(0)
                    break
                }
                messageCondition.await()
            }
        }
        return Pair(messageId,retrieveRequest(messageId)?.getSString())
    }

    private val replayLock = ReentrantLock()
    private val replayCondition = replayLock.newCondition()
    private val replayIdList = ArrayList<Int>()
    override fun onResponse(reqId: Int, bf: ByteArray, size: Int): Boolean {
        replayLock.withLock {
            replayIdList.add(reqId)
            replayCondition.signalAll()
        }
        return true
    }
    fun getAvailableReplay():Pair<Int,String?> {
        var replayId = 0
        replayLock.withLock {
            while (isBridgeAlive) {
                if (replayIdList.size > 0) {
                    replayId = replayIdList.removeAt(0)
                    break
                }
                replayCondition.await()
            }
        }
        return Pair(replayId,retrieveResponse(replayId)?.getSString())
    }

    protected fun clearMessages() {
        messageIdList.clear()
        clearRequests(); clearResponse()
        messageLock.withLock { messageCondition.signalAll() }
        replayLock.withLock { replayCondition.signalAll() }
    }

    override fun startBridgeLooper(): Int {
        val res = super.startBridgeLooper()
        clearMessages()
        return res
    }

    companion object{
        fun chatSender(brg:ChatBridge){
            val screenReader = Scanner(System.`in`)
            val msgBuffer = ByteArray(1000)
            while(brg.isBridgeAlive){
                print("\rYou: ")
                val msg = screenReader.nextLine()
                if(msg=="exit") break
                else {
                    msgBuffer.putSString(0, msg)
                    brg.sendRequest(msgBuffer, 0, msg.length + 2)
                    // println("\rMessage sent at ${System.currentTimeMillis()}")
                }
            }
        }

        fun chatReceiver(brg:ChatBridge, fromName:String){
            while(brg.isBridgeAlive){
                val (_,msg) = brg.getAvailableMessage()
                if(msg!=null){
                    print("\r$fromName: $msg\nYou: ")
                    // at ${System.currentTimeMillis()}\nYou: ")
                }
            }
        }
    }
}