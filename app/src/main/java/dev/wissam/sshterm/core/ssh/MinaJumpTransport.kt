package dev.wissam.sshterm.core.ssh

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.apache.sshd.client.channel.ChannelDirectTcpip
import org.apache.sshd.client.session.SessionFactory
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.FactoryManager
import org.apache.sshd.common.io.*
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.common.util.closeable.AbstractCloseable

/**
 * Adapts exactly one direct-tcpip channel to MINA's client transport. There is no
 * listening socket, ephemeral port, or local route into the authenticated bastion.
 * Explicit user forwarding still uses the normal NIO2 connector/acceptor.
 */
internal class MinaJumpTransport(private val channel: ChannelDirectTcpip) : Nio2ServiceFactoryFactory() {
    override fun create(manager: FactoryManager): IoServiceFactory {
        val network = super.create(manager)
        return object : IoServiceFactory by network {
            override fun createConnector(handler: IoHandler): IoConnector =
                if (handler is SessionFactory) JumpConnector(channel, handler)
                else network.createConnector(handler)
        }
    }
}

internal class JumpConnector(
    private val channel: ChannelDirectTcpip,
    private val handler: IoHandler,
    private val beforeAttach: () -> Unit = {},
) : AbstractCloseable(), IoConnector {
    private val used = AtomicBoolean(false)
    private val session = AtomicReference<JumpIoSession?>()
    private var listener: IoServiceEventListener? = null
    override fun getIoServiceEventListener() = listener
    override fun setIoServiceEventListener(value: IoServiceEventListener?) { listener = value }
    override fun getManagedSessions(): Map<Long, IoSession> =
        session.get()?.takeUnless { it.isClosed }?.let { mapOf(it.id to it) } ?: emptyMap()

    override fun connect(targetAddress: SocketAddress, context: AttributeRepository?, localAddress: SocketAddress?): IoConnectFuture {
        val result = DefaultIoConnectFuture(targetAddress, null)
        if (!used.compareAndSet(false, true) || isClosing || isClosed) {
            result.setException(IOException("Jump transport is closed or already used"))
            return result
        }
        result.addListener { if (it.isCanceled) close(true) }
        Thread({
            val io = JumpIoSession(this, channel, handler, targetAddress)
            try {
                session.set(io)
                if (isClosing || isClosed || result.isCanceled) throw IOException("Jump connection cancelled")
                if (context != null) io.setAttribute(AttributeRepository::class.java, context)
                beforeAttach()
                io.attach()
                if (isClosing || isClosed || io.isClosing || result.isCanceled) throw IOException("Jump connection cancelled")
                result.session = io
                io.readLoop()
            } catch (e: Exception) {
                result.setException(e)
            } finally {
                // Cancellation can close the connector before this thread publishes its session.
                // Close our own reference even if the connector's close already completed.
                try { io.close(true) } finally { close(true) }
            }
        }, "MinaJumpReader").apply { isDaemon = true; start() }
        return result
    }

    override fun doCloseImmediately() {
        try {
            session.getAndSet(null)?.close(true)
        } finally {
            try { channel.close(true) } finally { super.doCloseImmediately() }
        }
    }
}

private class JumpIoSession(
    private val connector: IoService,
    private val channel: ChannelDirectTcpip,
    private val handler: IoHandler,
    private val target: SocketAddress,
) : AbstractCloseable(), IoSession {
    private val sessionId = ids.incrementAndGet()
    private val attributes = ConcurrentHashMap<Any, Any>()
    private val writes = Any()
    private val lifecycle = Any()
    private var attaching = false
    private var attached = false
    private var closeNotified = false

    fun attach() = synchronized(lifecycle) {
        if (isClosing || isClosed) throw IOException("Jump connection cancelled before attachment")
        attaching = true
        try {
            handler.sessionCreated(this)
            attached = true
        } finally {
            attaching = false
            // A handler may close the I/O session reentrantly while constructing its SSH session.
            if (isClosing || isClosed) notifyClosed()
        }
    }

    private fun notifyClosed() {
        // MINA may attach a session and then throw from its creation/start callbacks.
        val hasSession = attached || (handler is SessionFactory &&
            org.apache.sshd.common.session.helpers.AbstractSession.getSession(this, true) != null)
        if (!closeNotified && hasSession) {
            closeNotified = true
            handler.sessionClosed(this)
        }
    }
    private val reads = java.util.concurrent.locks.ReentrantLock()
    private val readable = reads.newCondition()
    private var paused = false

    override fun getId() = sessionId
    override fun getService() = connector
    override fun getLocalAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 0) // metadata only; never bound
    override fun getRemoteAddress() = target
    override fun getAcceptanceAddress(): SocketAddress? = null
    override fun getAttribute(key: Any): Any? = attributes[key]
    override fun setAttribute(key: Any, value: Any): Any? = attributes.put(key, value)
    override fun setAttributeIfAbsent(key: Any, value: Any): Any? = attributes.putIfAbsent(key, value)
    override fun removeAttribute(key: Any): Any? = attributes.remove(key)

    override fun writeBuffer(buffer: Buffer): IoWriteFuture = synchronized(writes) {
        if (isClosing || isClosed) return@synchronized AbstractIoWriteFuture.fulfilled(id, IOException("Jump closed"))
        try {
            channel.invertedIn.write(buffer.array(), buffer.rpos(), buffer.available())
            channel.invertedIn.flush()
            AbstractIoWriteFuture.fulfilled(id, true)
        } catch (e: Exception) {
            close(true)
            AbstractIoWriteFuture.fulfilled(id, e)
        }
    }

    fun readLoop() {
        val bytes = ByteArray(32 * 1024)
        try {
            while (!isClosing && !isClosed) {
                reads.lock()
                try { while (paused && !isClosing && !isClosed) readable.await() }
                finally { reads.unlock() }
                if (isClosing || isClosed) break
                val count = channel.invertedOut.read(bytes)
                if (count < 0) break
                if (count > 0) handler.messageReceived(this, ByteArrayBuffer(bytes, 0, count))
            }
        } catch (e: Exception) {
            if (!isClosing && !isClosed) runCatching { handler.exceptionCaught(this, e) }
        } finally {
            close(true)
        }
    }

    override fun shutdownOutputStream() { channel.invertedIn.close() }
    override fun suspendRead() { reads.lock(); try { paused = true } finally { reads.unlock() } }
    override fun resumeRead() { reads.lock(); try { paused = false; readable.signalAll() } finally { reads.unlock() } }
    override fun doCloseImmediately() {
        try {
            resumeRead()
            try { channel.close(true) } finally {
                synchronized(lifecycle) {
                    // Other threads wait for attachment; a reentrant close is notified by attach().
                    if (!attaching) notifyClosed()
                }
            }
        } finally { super.doCloseImmediately() }
    }
    private companion object { val ids = AtomicLong() }
}
