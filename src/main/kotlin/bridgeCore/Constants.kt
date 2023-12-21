package bridgeCore

const val appPort = 43811
const val listenConnectTimeout = 5000
const val defaultBeatInterval = 1000L

const val StartLooperSignal: Byte = 0
const val BeatSignal: Byte = 1
const val StopLooperSignal: Byte = 15

const val StoppedBySelf = 0
const val StoppedByPeer = 1
const val ErrorByBridgeAlive = -1
const val ErrorByUnreliableConnection = -2
const val ErrorByNetworkTimeout = -3
const val ErrorByUnintendedSignal = -4
const val ErrorByStreamClosed = -5
const val ErrorByUnexpectedException = -6
