/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package examples

enum class BridgeState {
    Idle, StartingListen, Listening,
    Connecting, Connected,
    Disconnecting, StopingListen
}

const val appPort = 43811
const val listenConnectTimeout = 5000
const val InitializeCode = 45.toByte()
