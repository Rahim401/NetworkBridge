package examples

enum class BridgeState {
    Idle, StartingListen, Listening,
    Connecting, Connected,
    Disconnecting, StopingListen
}

const val appPort = 43811
const val listenConnectTimeout = 5000
const val InitializeCode = 45.toByte()
