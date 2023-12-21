import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.math.min
import java.io.IOException


fun DataOutputStream.writeData(vararg dataLst:Any){
    dataLst.forEach { data ->
        when (data) {
            is Byte -> writeByte(data.toInt())
            is Short -> writeShort(data.toInt())
            is Int -> writeInt(data)
            is Long -> writeLong(data)
            is Float -> writeFloat(data)
            is Double -> writeDouble(data)
            is String -> writeUTF(data)
            is ByteArray -> write(data)
        }
    }
}

fun Byte.toPInt() = toInt() and 0xFF
fun ByteArray.putBytes(vararg vls:Byte, from:Int=0): ByteArray{
    val loopTill = size-from
    vls.forEachIndexed { index, byte ->
        if(index>=loopTill)
            return@forEachIndexed
        set(from+index, byte)
    }
    return this
}
fun ByteArray.putShort(idx:Int=0, value: Short): ByteArray{
    set(idx,(value.toInt() shr 8).toByte())
    set(idx+1,value.toByte())
    return this
}
fun ByteArray.putInt(idx:Int=0, value: Int): ByteArray{
    set(idx,(value shr 24).toByte())
    set(idx+1,(value shr 16).toByte())
    set(idx+2,(value shr 8).toByte())
    set(idx+3,value.toByte())
    return this
}
fun ByteArray.putLong(idx:Int=0, value: Long): ByteArray{
    set(idx,(value shr 56).toByte())
    set(idx+1,(value shr 48).toByte())
    set(idx+2,(value shr 40).toByte())
    set(idx+3,(value shr 32).toByte())
    set(idx+4,(value shr 24).toByte())
    set(idx+5,(value shr 16).toByte())
    set(idx+6,(value shr 8).toByte())
    set(idx+7,value.toByte())
    return this
}
fun ByteArray.putUTF(idx:Int=0, value: String): ByteArray{
    val len = value.length.toShort()
    putShort(idx,len)
    value.encodeToByteArray()
        .copyInto(this,idx+2)
    return this
}
fun ByteArray.putString(idx:Int=0, value: String, len:Int=value.length): ByteArray{
    value.encodeToByteArray()
        .copyInto(this,idx,0,len)
    return this
}
fun ByteArray.putBString(idx:Int=0, value: String, maxLen:Int=value.length): Byte{
    val len = min(maxLen,size-idx-1).toByte()
    set(idx,len)
    putString(idx+1,value,len.toInt())
    return len
}
fun ByteArray.putSString(idx:Int=0, value: String, maxLen:Int=value.length): Short{
    val len = min(maxLen,size-idx-2).toShort()
    putShort(idx,len)
    putString(idx+2,value,len.toInt())
    return len
}
fun ByteArray.pad(idx: Int=0,length:Int): ByteArray{
    for(i in idx until (idx+length))
        set(i,0)
    return this
}


fun ByteArray.getBInt(idx: Int) = get(idx).toInt().and(0xFF)
fun ByteArray.getBLong(idx: Int) = get(idx).toLong().and(0xFF)
fun ByteArray.getShort(idx:Int=0):Short = getBInt(idx).shl(8)
        .or(getBInt(idx+1))
        .toShort()
fun ByteArray.getInt(idx:Int=0):Int = getBInt(idx).shl(24)
        .or(getBInt(idx+1).shl(16))
        .or(getBInt(idx+2).shl(8))
        .or(getBInt(idx+3))
fun ByteArray.getLong(idx:Int=0):Long = getBLong(idx).shl(56)
        .or(getBLong(idx+1).shl(48))
        .or(getBLong(idx+2).shl(40))
        .or(getBLong(idx+3).shl(32))
        .or(getBLong(idx+4).shl(24))
        .or(getBLong(idx+5).shl(16))
        .or(getBLong(idx+6).shl(8))
        .or(getBLong(idx+7))
fun ByteArray.getString(idx:Int=0,length: Int=size)
        = decodeToString(idx,idx+length)
fun ByteArray.getBString(idx:Int=0)
        = decodeToString(idx+1,idx+1+get(idx))
fun ByteArray.getSString(idx:Int=0)
        = decodeToString(idx+2,idx+2+getShort(idx))
fun ByteBuffer.getUTF(pos:Int=position()) =
    array().decodeToString(pos,pos + short)
fun String.toSArray() = ByteArray(length+2).apply {
    putSString(0,this@toSArray,length)
}