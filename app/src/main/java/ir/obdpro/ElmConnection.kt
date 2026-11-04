package ir.obdpro

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class ElmConnection {
    private var socket: BluetoothSocket? = null
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile var connected = false
        private set

    fun pairedDevices(): List<BluetoothDevice> =
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()

    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        disconnect()
        val s = device.createRfcommSocketToServiceRecord(spp)
        s.connect()
        socket = s
        connected = true
        initialize()
    }

    private suspend fun initialize() {
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach {
            try { command(it, 1800) } catch (_: Exception) {}
        }
    }

    suspend fun command(cmd: String, timeoutMs: Long = 2500): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("آداپتور متصل نیست")
        if (!s.isConnected) throw IOException("ارتباط بلوتوث قطع شده است")
        val out = s.outputStream
        val input = s.inputStream
        out.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val buffer = ByteArray(1024)
        val result = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (input.available() > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, input.available().coerceAtLeast(1)))
                if (n > 0) {
                    result.append(String(buffer, 0, n, Charsets.US_ASCII))
                    if (result.contains(">")) break
                }
            } else Thread.sleep(15)
        }
        result.toString()
    }

    fun disconnect() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
