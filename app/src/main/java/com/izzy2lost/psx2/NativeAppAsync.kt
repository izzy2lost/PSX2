// SPDX-FileCopyrightText: 2025 Android Port Contributors
// SPDX-License-Identifier: GPL-3.0+

package com.izzy2lost.psx2

import kotlinx.coroutines.*

object NativeAppAsync {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface Callback<T> {
        fun onResult(result: T)
    }

    @JvmStatic
    fun getGameSerial(gameUri: String, callback: Callback<String>) {
        scope.launch {
            val result = NativeApp.getGameSerialSafe(gameUri)
            withContext(Dispatchers.Main) {
                callback.onResult(result)
            }
        }
    }

    @JvmStatic
    fun getGameTitleFromUri(gameUri: String, callback: Callback<String>) {
        scope.launch {
            val result = NativeApp.getGameTitleFromUriSafe(gameUri)
            withContext(Dispatchers.Main) {
                callback.onResult(result)
            }
        }
    }

    @JvmStatic
    fun getGameCrc(gameUri: String, callback: Callback<String>) {
        scope.launch {
            val result = NativeApp.getGameCrcSafe(gameUri)
            withContext(Dispatchers.Main) {
                callback.onResult(result)
            }
        }
    }

    // Helper to run any blocking operation on Dispatchers.IO and return result to UI Thread via callback
    @JvmStatic
    fun <T> runOnIO(block: java.util.concurrent.Callable<T>, callback: Callback<T>) {
        scope.launch {
            val result = block.call()
            withContext(Dispatchers.Main) {
                callback.onResult(result)
            }
        }
    }
}
