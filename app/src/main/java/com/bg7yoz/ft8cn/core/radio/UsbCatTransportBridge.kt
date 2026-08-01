package com.bg7yoz.ft8cn.core.radio

import android.content.Context
import com.bg7yoz.ft8cn.connector.CableSerialPort
import com.bg7yoz.ft8cn.connector.OnConnectorStateChanged
import com.bg7yoz.ft8cn.serialport.UsbSerialPort
import com.bg7yoz.ft8cn.serialport.util.SerialInputOutputManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class UsbCatEndpoint(
    val token: String,
    val label: String,
)

/** 扫描 Android USB Host 串口，不读取或显示设备序列号。 */
object UsbCatEndpointScanner {
    fun scan(context: Context): List<UsbCatEndpoint> = CableSerialPort.listSerialPorts(context).map { port ->
        UsbCatEndpoint(
            token = encode(port),
            label = String.format(
                java.util.Locale.US,
                "USB %04X:%04X · 端口 %d",
                port.vendorId,
                port.productId,
                port.portNum + 1,
            ),
        )
    }

    fun isUsbToken(value: String): Boolean = value.startsWith(USB_PREFIX)

    internal fun decode(value: String): CableSerialPort.SerialPort {
        val fields = value.removePrefix(USB_PREFIX).split('/')
        require(fields.size == 4) { "USB CAT 端点格式无效" }
        return CableSerialPort.SerialPort(
            fields[0].toInt(),
            fields[1].toInt(),
            fields[2].toInt(),
            fields[3].toInt(),
        )
    }

    private fun encode(port: CableSerialPort.SerialPort): String =
        "$USB_PREFIX${port.deviceId}/${port.vendorId}/${port.productId}/${port.portNum}"

    private const val USB_PREFIX = "usb://"
}

/**
 * Hamlib 不能直接消费 Android UsbDeviceConnection，因此使用仅绑定回环地址的字节桥。
 * 关闭电台或 USB 断开时会同步释放 socket、USB 连接和固定 4 KiB 工作区。
 */
class UsbCatTransportBridge(context: Context) {
    private val applicationContext = context.applicationContext
    private val closing = AtomicBoolean(false)
    private val socketWriteLock = Any()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serialPort: CableSerialPort? = null
    private var bridgeThread: Thread? = null

    suspend fun open(
        token: String,
        baud: Int,
        dataBits: Int,
        stopBits: Int,
        parity: Int = UsbSerialPort.PARITY_NONE,
    ): String = withContext(Dispatchers.IO) {
        close()
        closing.set(false)
        val endpoint = UsbCatEndpointScanner.decode(token)
        val connected = CompletableDeferred<Unit>()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { serverSocket = it }
        val cable = CableSerialPort(
            applicationContext,
            endpoint,
            baud,
            if (dataBits == 0) 8 else dataBits,
            if (stopBits == 0) 1 else stopBits,
            parity,
            object : OnConnectorStateChanged {
                override fun onDisconnected() {
                    if (!closing.get() && !connected.isCompleted) {
                        connected.completeExceptionally(IllegalStateException("USB CAT 已断开"))
                    }
                    closeSockets()
                }

                override fun onConnected() {
                    if (!connected.isCompleted) connected.complete(Unit)
                }

                override fun onRunError(message: String) {
                    if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException(message))
                    closeSockets()
                }
            },
        ).also { serialPort = it }
        cable.ioListener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                synchronized(socketWriteLock) {
                    runCatching { clientSocket?.getOutputStream()?.write(data) }
                        .onFailure { closeSockets() }
                }
            }

            override fun onRunError(e: Exception) {
                if (!connected.isCompleted) connected.completeExceptionally(e)
                closeSockets()
            }
        }
        bridgeThread = Thread({ bridgeLoop(server, cable) }, "ft8cn-usb-cat-bridge").apply {
            isDaemon = true
            start()
        }
        if (!cable.connect() && !connected.isCompleted) {
            connected.completeExceptionally(IllegalStateException("无法打开 USB CAT 串口"))
        }
        try {
            withTimeout(30_000L) { connected.await() }
        } catch (error: Throwable) {
            close()
            throw error
        }
        "tcp://127.0.0.1:${server.localPort}"
    }

    fun close() {
        closing.set(true)
        serialPort?.ioListener = null
        runCatching { serialPort?.disconnect() }
        serialPort = null
        closeSockets()
        bridgeThread?.interrupt()
        bridgeThread = null
    }

    fun setDtr(enabled: Boolean) {
        check(serialPort?.setDTR_On(enabled) == true) { "USB 串口不支持 DTR 控制" }
    }

    fun setRts(enabled: Boolean) {
        check(serialPort?.setRTS_On(enabled) == true) { "USB 串口不支持 RTS 控制" }
    }

    private fun bridgeLoop(server: ServerSocket, cable: CableSerialPort) {
        runCatching {
            val socket = server.accept().also {
                it.tcpNoDelay = true
                clientSocket = it
            }
            val buffer = ByteArray(BRIDGE_BUFFER_BYTES)
            val input = socket.getInputStream()
            while (!closing.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0 && !cable.sendData(buffer.copyOf(count))) break
            }
        }
        closeSockets()
    }

    private fun closeSockets() {
        synchronized(socketWriteLock) {
            runCatching { clientSocket?.close() }
            clientSocket = null
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    private companion object {
        const val BRIDGE_BUFFER_BYTES = 4 * 1024
    }
}
