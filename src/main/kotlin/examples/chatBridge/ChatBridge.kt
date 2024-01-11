/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package examples.chatBridge

import bridgeCore.RequestBridge
import getSString
import putSString
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock

/**
 * An abstract class on top of Request bridge to send and receive message simultaneously
 * in a thread-safe way
 */
abstract class ChatBridge: RequestBridge() {
    private val messageLock = ReentrantLock()
    private val messageCondition = messageLock.newCondition()
    private val messageIdList = ArrayList<Int>()
    override fun onRequest(resId: Int, reqId: Int, bf: ByteArray, size: Int): Boolean {
        messageLock.withLock {
            messageIdList.add(resId)
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
    override fun onResponse(resId: Int, bf: ByteArray, size: Int): Boolean {
        replayLock.withLock {
            replayIdList.add(resId)
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

    private fun clearMessages() {
        messageLock.withLock { messageIdList.clear(); messageCondition.signalAll() }
        replayLock.withLock { replayIdList.clear(); replayCondition.signalAll() }
    }

    override fun startBridgeLooper(): Int {
        val res = super.startBridgeLooper()
        clearMessages()
        return res
    }

    companion object{
        fun chatSender(brg: ChatBridge){
            val screenReader = Scanner(System.`in`)
            val msgBuffer = ByteArray(1000)
            while(brg.isBridgeAlive){
                print("\rYou: ")
                val msg = screenReader.nextLine()
                if(msg=="exit") break
                else {
                    msgBuffer.putSString(0, msg)
                    brg.sendRequest(msgBuffer, 0, msg.length + 2)
                }
            }
        }

        fun chatReceiver(brg: ChatBridge, fromName:String){
            while(brg.isBridgeAlive){
                val (_,msg) = brg.getAvailableMessage()
                if(msg!=null){
                    print("\r$fromName: $msg\nYou: ")
                }
            }
        }
    }
}