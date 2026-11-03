package com.example.shelfplayer.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Optional, read-only WF-1000XM6 evidence source. A tap is evidence only, never a Play command.
 * Lifecycle and address-to-live-output resolution belong to the service's main dispatcher.
 */
internal class SonyTapMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val onTap: (outputId: String) -> Unit,
    private val outputForAddress: (String) -> String?,
) {
    private var candidates = emptySet<String>()
    private var enabled = false
    private var closed = false
    private var attempted: Set<String>? = null
    private var socket: BluetoothSocket? = null
    private var connected = false
    private var peer: BluetoothDevice? = null
    private var peerOutput: String? = null
    private var reader: Job? = null
    private var cancellation: Job? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == SOUND_CONNECT) {
                // Package transactions invalidate the entire session, even if the query races the change.
                stop()
            }
        }
    }

    @MainThread
    fun update(enabled: Boolean, candidateIds: Set<String>) {
        if (closed) return
        val changed = this.enabled != enabled || candidates != candidateIds
        this.enabled = enabled
        candidates = candidateIds.toSet()
        if (changed) {
            stop()
            attempted = null
        }
        if (!enabled || candidates.isEmpty() || !allowed()) {
            stop()
            return
        }
        val target = target()
        if (target == null) {
            stop()
            return
        }
        if (reader != null || attempted == candidates || !registerPackageReceiver()) return
        attempted = candidates.toSet()
        start(target.first, target.second)
    }

    private fun registerPackageReceiver(): Boolean {
        if (registered) return true
        return try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun allowed(): Boolean = enabled && !closed && scope.isActive &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) && soundConnectAllows(context.packageManager)

    @SuppressLint("MissingPermission")
    private fun target(): Pair<BluetoothDevice, String>? = try {
        if (!allowed()) {
            null
        } else {
            val bonded = context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            val matching = bonded.filter { it.name == MODEL }.mapNotNull { device ->
                outputForAddress(device.address)?.takeIf { it in candidates }?.let { device to it }
            }
            matching.singleOrNull()
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun start(device: BluetoothDevice, outputId: String) {
        val connection = try {
            device.createRfcommSocketToServiceRecord(MDR_SERVICE)
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }
        socket = connection
        peer = device
        peerOutput = outputId
        // This independent child closes a blocking read/connect immediately when the service scope ends.
        cancellation = scope.launch(main, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                closeSocket(connection)
            }
        }
        reader = scope.launch(io) { read(connection, device, outputId) }
    }

    private suspend fun read(connection: BluetoothSocket, device: BluetoothDevice, outputId: String) = withContext(io) {
        val timeout = scope.launch(main) {
            delay(CONNECT_TIMEOUT_MS)
            closeSocket(connection)
        }
        try {
            if (!withContext(main) { valid(connection, device, outputId) }) return@withContext
            connection.connect()
            timeout.cancel()
            delay(PROTOCOL_START_DELAY_MS)
            if (!withContext(main) { valid(connection, device, outputId) }) return@withContext
            withContext(main) { connected = socket === connection }
            val input = BufferedInputStream(connection.inputStream)
            val output = BufferedOutputStream(connection.outputStream)
            SonyMdrProtocol.inquiry(output)
            while (isActive && consumeFrame(input, output, connection, device, outputId)) {
                // Keep blocking I/O on this optional adapter coroutine until the peer or session ends.
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // End this attempt; no retry or raw peer/error logging.
        } catch (_: SecurityException) {
            // Permission revocation fails closed.
        } catch (_: IllegalStateException) {
            // Platform session failures fail closed.
        } finally {
            timeout.cancel()
            closeSocket(connection)
            withContext(NonCancellable + main) {
                if (socket === connection) connected = false
            }
        }
    }

    private suspend fun consumeFrame(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        connection: BluetoothSocket,
        device: BluetoothDevice,
        outputId: String,
    ): Boolean {
        val frame = SonyMdrProtocol.read(input) ?: return false
        if (!withContext(main) { valid(connection, device, outputId) }) return false
        SonyMdrProtocol.acknowledge(output, frame)
        if (frame.isRightTap()) {
            withContext(main) {
                if (canUseTap(outputId)) onTap(outputId)
            }
        }
        return true
    }

    /** Recheck at the correlated Play commit boundary; cached tap evidence is insufficient. */
    @MainThread
    fun canUseTap(id: String): Boolean {
        if (peerOutput != id) return false
        val connection = socket
        val device = peer
        val usable = connected && connection != null && device != null && peerOutput == id &&
            valid(connection, device, id)
        if (!usable) stop()
        return usable
    }

    @SuppressLint("MissingPermission")
    private fun valid(connection: BluetoothSocket, device: BluetoothDevice, outputId: String): Boolean = try {
        socket === connection && allowed() && outputId in candidates &&
            target()?.let { it.first.address == device.address && it.second == outputId } == true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun stop() {
        connected = false
        peer = null
        peerOutput = null
        socket?.let(::closeSocket)
        socket = null
        reader?.cancel()
        reader = null
        cancellation?.cancel()
        cancellation = null
    }

    @MainThread
    fun close() {
        closed = true
        stop()
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    private fun closeSocket(connection: BluetoothSocket) {
        try {
            connection.close()
        } catch (_: IOException) {
            // Closing is best effort and never logs platform messages containing peer identifiers.
        }
    }

    internal companion object {
        const val SOUND_CONNECT = "com.sony.songpal.mdr"
        private const val MODEL = "WF-1000XM6"
        private const val CONNECT_TIMEOUT_MS = 6500L
        private const val PROTOCOL_START_DELAY_MS = 450L
        private val MDR_SERVICE = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2")

        @Suppress("TooGenericExceptionCaught") // Unknown package/visibility failures must deny access.
        fun soundConnectAllows(packages: PackageManager): Boolean = try {
            !packages.getApplicationInfo(SOUND_CONNECT, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
