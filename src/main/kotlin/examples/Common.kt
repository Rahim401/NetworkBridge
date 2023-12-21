package examples

enum class BridgeState {
    Idle, StartingListen, Listening,
    Connecting, Connected,
    Disconnecting, StopingListen
}
const val InitializeCode = 45.toByte()
