import java.io.*
import java.nio.ByteBuffer
import kotlin.math.min


fun OutputStream.writeData(vararg dataLst:Any) {
    dataLst.forEach { data ->
        when (data) {
            is Byte -> write(data.toInt())
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

fun OutputStream.writeShort(value: Int) {
    write(value shr 8); write(value)
}
fun OutputStream.writeInt(value: Int) {
    write(value shr 24); write(value shr 16)
    write(value shr 8); write(value)
}
fun OutputStream.writeLong(value: Long) {
    writeInt((value shr 32).toInt())
    writeInt(value.toInt())
}
fun OutputStream.writeFloat(value: Float) = writeInt(value.toBits())
fun OutputStream.writeDouble(value: Double) = writeLong(value.toBits())
fun OutputStream.writeUTF(value: String) {
    val encodedString = value.encodeToByteArray()
    if (encodedString.size < 65535) {
        writeShort(encodedString.size)
        write(encodedString)
    }
}

fun InputStream.readByte() = read().toByte()
fun InputStream.readUShort() = read().shl(8).or(read())
fun InputStream.readShort() = read().shl(8).or(read()).toShort()
fun InputStream.readInt() = readUShort().shl(16).or(readUShort())
fun InputStream.readLong() = readInt().toLong().shl(32).or(readInt().toLong())
fun InputStream.readFloat() = Float.fromBits(readInt())
fun InputStream.readDouble() = Double.fromBits(readLong())
fun InputStream.readUTF() = readNBytes(readUShort()).decodeToString()

fun Byte.toPInt() = toInt() and 0xFF
fun ByteArray.putBytes(vararg vls:Byte, from:Int=0):ByteArray {
    val loopTill = size-from
    vls.forEachIndexed { index, byte ->
        if(index>=loopTill)
            return@forEachIndexed
        set(from+index, byte)
    }
    return this
}
fun ByteArray.putShort(idx:Int=0, value: Short): ByteArray {
    set(idx,(value.toInt() shr 8).toByte())
    set(idx+1,value.toByte())
    return this
}
fun ByteArray.putInt(idx:Int=0, value: Int):ByteArray {
    set(idx,(value shr 24).toByte())
    set(idx+1,(value shr 16).toByte())
    set(idx+2,(value shr 8).toByte())
    set(idx+3,value.toByte())
    return this
}
fun ByteArray.putFloat(idx:Int=0, value: Float) = putInt(idx, value.toBits())
fun ByteArray.putLong(idx:Int=0, value: Long):ByteArray {
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
fun ByteArray.putDouble(idx:Int=0, value: Double) = putLong(idx, value.toBits())
fun ByteArray.putUTF(idx:Int=0, value: String):ByteArray {
    val len = value.length.toShort()
    putShort(idx,len)
    value.encodeToByteArray()
        .copyInto(this,idx+2)
    return this
}
fun ByteArray.putString(idx:Int=0, value: String, len:Int=value.length):ByteArray {
    value.encodeToByteArray()
        .copyInto(this,idx,0,len)
    return this
}
fun ByteArray.putBString(idx:Int=0, value: String, maxLen:Int=value.length):Int {
    val len = min(maxLen,size-idx-1)
    set(idx,len.toByte())
    putString(idx+1,value, len)
    return len + 1
}
fun ByteArray.putSString(idx:Int=0, value: String, maxLen:Int=value.length):Int {
    val len = min(maxLen,size-idx-2)
    putShort(idx,len.toShort())
    putString(idx+2,value, len)
    return len + 2
}
fun ByteArray.pad(idx: Int=0,length:Int):ByteArray {
    for(i in idx until (idx+length))
        set(i,0)
    return this
}


fun ByteArray.getBInt(idx: Int) = get(idx).toInt().and(0xFF)
fun ByteArray.getBLong(idx: Int) = get(idx).toLong().and(0xFF)
fun ByteArray.getUShort(idx:Int=0):Int =
    getBInt(idx).shl(8).or(getBInt(idx+1))
fun ByteArray.getShort(idx:Int=0):Short = getUShort(idx).toShort()
fun ByteArray.getInt(idx:Int=0):Int = getBInt(idx).shl(24)
        .or(getBInt(idx+1).shl(16))
        .or(getBInt(idx+2).shl(8))
        .or(getBInt(idx+3))
fun ByteArray.getFloat(idx:Int=0):Float = Float.fromBits(getInt(idx))
fun ByteArray.getLong(idx:Int=0):Long = getBLong(idx).shl(56)
        .or(getBLong(idx+1).shl(48))
        .or(getBLong(idx+2).shl(40))
        .or(getBLong(idx+3).shl(32))
        .or(getBLong(idx+4).shl(24))
        .or(getBLong(idx+5).shl(16))
        .or(getBLong(idx+6).shl(8))
        .or(getBLong(idx+7))
fun ByteArray.getDouble(idx:Int=0):Double = Double.fromBits(getLong(idx))
fun ByteArray.getString(idx:Int=0,length: Int=size) = decodeToString(idx,idx+length)
fun ByteArray.getBString(idx:Int=0) = decodeToString(idx+1,idx+1+get(idx))
fun ByteArray.getSString(idx:Int=0) = decodeToString(idx+2,idx+2+getShort(idx))
fun ByteBuffer.getUTF(pos:Int=position()) = array().decodeToString(pos,pos + short)
fun String.toSArray() = ByteArray(length+2).apply {
    putSString(0,this@toSArray,length)
}