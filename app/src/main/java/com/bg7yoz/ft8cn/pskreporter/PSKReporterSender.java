package com.bg7yoz.ft8cn.pskreporter;

import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PSKReporterSender
 */
public class PSKReporterSender {
    private static final String TAG = "PSKReporterSender";

    private final PSKReporterManager manager;
    private final PSKReporterPacketBuilder packetBuilder = new PSKReporterPacketBuilder();
    private final SecureRandom random = new SecureRandom();

    private ScheduledExecutorService scheduler;

    private volatile boolean running = false;
    private volatile boolean flushInProgress = false;

    private DatagramSocket socket;
    private int observationDomainId;
    private int sequenceNumber = 0;
    private int packetsSent = 0;
    private long lastTemplateSendMs = 0L;

    public PSKReporterSender(PSKReporterManager manager) {
        this.manager = manager;
        this.observationDomainId = random.nextInt(Integer.MAX_VALUE);
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        running = true;

        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tickle();
            }
        }, 15, Math.max(30, GeneralVariables.pskReporterFlushIntervalMs / 1000), TimeUnit.SECONDS);

        Log.d(TAG, "sender started");
    }

    public synchronized void stop() {
        running = false;

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        closeSocket();
        Log.d(TAG, "sender stopped");
    }

    /**
     * 允许在队列堆积时主动触发一次发送。
     * 真正的网络 I/O 仍在 sender 线程里执行。
     */
    public void flushNow() {
        if (!running || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                tickle();
            }
        });
    }

    private void tickle() {
        if (!running || flushInProgress || !GeneralVariables.enablePskReporter) {
            return;
        }

        flushInProgress = true;
        try {
            PSKReporterConfig config = PSKReporterConfig.fromGlobals();
            if (!config.isValid()) {
                return;
            }

            ensureSocket();
            if (socket == null) {
                return;
            }

            while (manager.getQueueSize() > 0) {
                ArrayList<PSKReporterSpot> batch = manager.drainBatch(config.maxRecordsPerPacket);
                if (batch == null || batch.isEmpty()) {
                    break;
                }

                boolean includeTemplates = shouldIncludeTemplates();
                int exportTimeSeconds = (int) (System.currentTimeMillis() / 1000L);

                byte[] packet = packetBuilder.buildPacket(
                        config,
                        batch,
                        includeTemplates,
                        exportTimeSeconds,
                        sequenceNumber,
                        observationDomainId
                );

                if (packet == null || packet.length == 0) {
                    continue;
                }

                boolean sent = sendPacket(config, packet, batch);
                if (!sent) {
                    manager.requeue(batch);
                    break;
                }

                // IPFIX sequence number: 递增本次 packet 中包含的 data records 数量
                sequenceNumber += batch.size();
                packetsSent++;

                if (includeTemplates) {
                    lastTemplateSendMs = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "tickle error: " + e.getMessage(), e);
        } finally {
            flushInProgress = false;
        }
    }

    private boolean shouldIncludeTemplates() {
        long now = System.currentTimeMillis();

        // 启动后前几包重复带模板
        if (packetsSent < 3) {
            return true;
        }

        // 之后每小时重发一次模板
        return now - lastTemplateSendMs >= 60L * 60L * 1000L;
    }

    private void ensureSocket() {
        if (socket != null && !socket.isClosed()) {
            return;
        }

        try {
            socket = new DatagramSocket();
            socket.setReuseAddress(true);
            Log.d(TAG, "socket opened, localPort=" + socket.getLocalPort());
        } catch (Exception e) {
            Log.e(TAG, "ensureSocket error: " + e.getMessage(), e);
            socket = null;
        }
    }

    private boolean sendPacket(PSKReporterConfig config, byte[] packet, ArrayList<PSKReporterSpot> batch) {
        try {
            InetAddress address = InetAddress.getByName(config.host);
            DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, address, config.port);
            socket.send(datagramPacket);

            if (batch != null && !batch.isEmpty()) {
                Log.d(TAG, "packet sent: bytes=" + packet.length
                        + " spots=" + batch.size()
                        + " host=" + config.host
                        + ":" + config.port
                        + " first=" + batch.get(0).toString());
            } else {
                Log.d(TAG, "packet sent: bytes=" + packet.length);
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "sendPacket error: " + e.getMessage(), e);
            return false;
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignore) {
            }
            socket = null;
        }
    }
}