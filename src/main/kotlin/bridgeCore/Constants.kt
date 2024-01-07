/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package bridgeCore

// All signal used across the Bridges
const val StartLooperSignal: Byte = 0
const val BeatSignal: Byte = 1
const val RqByteSignal:Byte = 2; const val RqShortSignal:Byte = 3
const val ResByteSignal:Byte = 4; const val ResShortSignal:Byte = 5
const val MakeStmSignal:Byte = 6
const val StopLooperSignal: Byte = -1


// Errors used across Bridges
const val ErrorByBridgeNotAlive = -1
const val ErrorByBridgeAlreadyAlive = -2
const val ErrorByConnectionTimeout = -3
const val ErrorByNetworkTimeout = -4
const val ErrorByUnintendedSignal = -5
const val ErrorByInvalidId = -6
const val ErrorByLimitReached = -7
const val ErrorByDataSizeExceeded = -8
const val ErrorOnStreamCreation = -9
const val ErrorOnStreamConnection = -10
const val ErrorByStreamUnavailable = -11
const val ErrorByStreamClosed = -31
const val ErrorByUnexpectedException = -32