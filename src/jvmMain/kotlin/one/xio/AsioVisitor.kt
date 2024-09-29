package one.xio


import one.xio.AsioVisitor.FSM.sslBacklog.fromApp
import one.xio.AsioVisitor.FSM.sslBacklog.fromNet
import one.xio.AsioVisitor.FSM.sslBacklog.toApp
import one.xio.AsioVisitor.FSM.sslBacklog.toNet

/**
 * User: jim
 * Date: 4/15/12
 * Time: 11:50 PM
 */
interface AsioVisitor {
    @kotlin.Throws(Exception::class)
    fun onRead(key: SelectionKey?)

    @kotlin.Throws(Exception::class)
    fun onConnect(key: SelectionKey?)

    @kotlin.Throws(Exception::class)
    fun onWrite(key: SelectionKey?)

    @kotlin.Throws(Exception::class)
    fun onAccept(key: SelectionKey?)

    object FSM {
        val DEBUG_SENDJSON: Boolean = Config.get("DEBUG_SENDJSON", "false").equals(
            "true"
        )
        var selectorThread: Thread? = null
        var selector: Selector? = null
        val sslState: Map<SelectionKey, SSLEngine> = WeakHashMap()

        /**
         * stores {InterestOps,{attachment}}
         */
        val sslGoal: Map<SelectionKey, Pair<Integer, Object>> = WeakHashMap()
        var executorService: ExecutorService? = null

        /**
         * handles SslEngine state NEED_TASK.creates a phaser and launches all threads with invokeAll
         */
        @kotlin.Throws(InterruptedException::class)
        fun delegateTasks(state: Pair<SelectionKey?, SSLEngine?>) {
            val key: SelectionKey = state.getA()
            val runnables: List<Callable<Void>> = ArrayList()
            var t: Runnable?
            val barrier: AtomicReference<CyclicBarrier> = AtomicReference()
            val sslEngine: SSLEngine = state.getB()
            while (null != (sslEngine.getDelegatedTask().also { t = it })) {
                val finalT1: Runnable = t
                runnables.add({
                    finalT1.run()
                    barrier.get().await(REALTIME_CUTOFF, REALTIME_UNIT)
                    null
                })
            }
            barrier.set(CyclicBarrier(runnables.size(), {
                try {
                    handShake(state)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }))

            kotlin.checkNotNull(executorService) { "must install FSM executorService!" }
            executorService.invokeAll(runnables)
        }

        /**
         * this is a beast.
         */
        @kotlin.Throws(Exception::class)
        fun handShake(state: Pair<SelectionKey?, SSLEngine?>) {
            if (!state.getA().isValid()) return
            val sslEngine: SSLEngine = state.getB()

            val handshakeStatus: HandshakeStatus = sslEngine.getHandshakeStatus()
            System.err.println("hs: " + handshakeStatus)

            when (handshakeStatus) {
                NEED_TASK -> delegateTasks(state)
                NOT_HANDSHAKING, FINISHED -> {
                    val key: SelectionKey = state.getA()
                    val integerObjectPair: Pair<Integer, Object> = sslGoal.remove(key)
                    sslState.put(key, sslEngine)
                    val a: Integer = integerObjectPair.getA()
                    val b: Object = integerObjectPair.getB()
                    key.interestOps(a).attach(b)
                    key.selector().wakeup()
                }

                NEED_WRAP -> needWrap(state)
                NEED_UNWRAP -> needUnwrap(state)
            }
        }

        @kotlin.Throws(Exception::class)
        fun needUnwrap(state: Pair<SelectionKey?, SSLEngine?>) {
            val fromNet: ByteBuffer? = fromNet.resume(state)
            val toApp: ByteBuffer? = toApp.resume(state)
            val sslEngine: SSLEngine = state.getB()
            val unwrap: SSLEngineResult = sslEngine.unwrap(fromNet.flip() as ByteBuffer?, toApp)
            System.err.println("" + unwrap)
            fromNet.compact()

            val status: Status = unwrap.getStatus()
            val key: SelectionKey = state.getA()
            when (status) {
                BUFFER_UNDERFLOW -> {
                    key.interestOps(OP_READ).attach(object : Impl() {
                        @kotlin.Throws(Exception::class)
                        override fun onRead(key: SelectionKey) {
                            val read: Int = (key.channel() as SocketChannel).read(fromNet)
                            if (-1 == read) {
                                key.cancel()
                            } else {
                                handShake(state)
                            }
                        }
                    })
                    key.selector().wakeup()
                }

                OK -> handShake(state)

                BUFFER_OVERFLOW -> handShake(state)
                CLOSED -> state.getA().cancel()
            }
        }

        @kotlin.Throws(Exception::class)
        fun needWrap(state: Pair<SelectionKey?, SSLEngine?>) {
            val toNet: ByteBuffer? = toNet.resume(state)
            val fromApp: ByteBuffer? = fromApp.resume(state)

            val sslEngine: SSLEngine = state.getB()
            val wrap: SSLEngineResult = sslEngine.wrap(bb(fromApp, flip), toNet)
            System.err.println("wrap: " + wrap)
            when (wrap.getStatus()) {
                BUFFER_UNDERFLOW -> throw Error("not supposed to happen here")
                OK -> {
                    val channel: SocketChannel = state.getA().channel() as SocketChannel
                    channel.write(toNet.flip() as ByteBuffer?)
                    toNet.compact()
                    fromApp.compact()
                    handShake(state)
                    return
                }

                BUFFER_OVERFLOW -> throw Error("buffer size impossible")
                CLOSED -> state.getA().cancel()
            }
        }

        internal enum class sslBacklog {
            fromNet, toNet, fromApp, toApp;

            val per: Map<SelectionKey, ByteBuffer> = WeakHashMap()

            fun resume(state: Pair<SelectionKey?, SSLEngine?>): ByteBuffer? {
                val key: SelectionKey = state.getA()
                var buffer: ByteBuffer? = on(key)
                if (null == buffer) {
                    val sslEngine: SSLEngine = state.getB()
                    on(
                        key,
                        ByteBuffer.allocateDirect(sslEngine.getSession().getPacketBufferSize()).also { buffer = it })
                }
                return buffer
            }

            fun on(key: SelectionKey): ByteBuffer? {
                return per.get(key)
            }

            fun on(key: SelectionKey, buffer: ByteBuffer?): SelectionKey {
                per.put(key, buffer)
                return key
            }
        }
    }

    object Helper {
        val NIL: Array<ByteBuffer> = arrayOf<ByteBuffer>()
        val REALTIME_UNIT: TimeUnit = TimeUnit.valueOf(Config.get("REALTIME_UNIT", TimeUnit.MINUTES.name()))
        val REALTIME_CUTOFF: Integer = Integer.parseInt(Config.get("REALTIME_CUTOFF", "3"))


        /**
         * called once client is connected, but before any bytes are read or written from socket.
         *
         * @param host        ssl remote host
         * @param port        ssl remote port
         * @param asioVisitor
         * @param clientOps   ussually OP_WRITE but OP_READ for non-http protocols as well
         * @throws Exception
         */
        @kotlin.Throws(Exception::class)
        fun sslClient2(host: String?, port: Int, asioVisitor: Impl?, clientOps: Int) {
            val open: SocketChannel = SocketChannel.open()
            open.configureBlocking(false)
            val remote: InetSocketAddress = InetSocketAddress(host, port)
            open.connect(remote)
            finishConnect(open, F { key: SelectionKey? ->
                log(key, "ssl", remote.toString())
                val sslEngine: SSLEngine = SSLContext.getDefault().createSSLEngine(host, port)
                sslEngine.setUseClientMode(true)
                sslEngine.setWantClientAuth(false)
                FSM.sslState.put(key, sslEngine)
                FSM.sslGoal.put(key, Pair.pair(clientOps, asioVisitor))
                FSM.needWrap(pair(key, sslEngine))
            })
        }

        @kotlin.Throws(Exception::class)
        fun sslClient2(uri: URI, asioVisitor: Impl?, clientOps: Int) {
            log(uri, "sslClient")
            var port: Int = uri.getPort()
            if (port == -1) port = 443
            val host: String = uri.getHost()
            sslClient2(host, port, asioVisitor, clientOps)
        }


        fun toRead(f: F): Impl {
            return object : Impl() {
                @kotlin.Throws(Exception::class)
                override fun onRead(key: SelectionKey?) {
                    //toRead begin
                    f.apply(key)
                    //toRead end
                }
            }
        }

        fun toRead(key: SelectionKey, f: F) {
            log(key, "toRead", f.toString())
            val sslEngine: SSLEngine? = FSM.sslState.get(key)
            key.interestOps(OP_READ).attach(toRead(f))
            if (null != sslEngine && sslBacklog.toApp.resume(pair(key, sslEngine)).hasRemaining()) {
                try {
                    f.apply(key)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else key.selector().wakeup()
        }

        fun toRead(key: SelectionKey, impl: Impl?) {
            //toRead
            key.interestOps(OP_READ).attach(impl)
            key.selector().wakeup()
        }

        fun toConnect(key: SelectionKey, f: F) {
            toConnect(key, toConnect(f))
        }

        fun toConnect(key: SelectionKey, impl: Impl) { //toConnect
            log(key, "toConnect", impl.toString())
            key.interestOps(OP_CONNECT).attach(impl)
            key.selector().wakeup()
        }

        @kotlin.Throws(Exception::class)
        fun finishConnect(host: String?, port: Int, onSuccess: F) {      //finishConnect

            val open: SocketChannel = SocketChannel.open()
            open.configureBlocking(false)
            open.connect(InetSocketAddress(host, port))
            finishConnect(open, onSuccess)
        }

        fun finishConnect(channel: SocketChannel, onSuccess: F) {
            //finishConnect
            SingleThreadSingletonServer.enqueue(
                channel, OP_CONNECT,
                toConnect(F { key: SelectionKey? ->
                    if (channel.finishConnect()) {
                        onSuccess.apply(key)
                    }
                })
            )
        }

        fun toWrite(key: SelectionKey, f: F) {
            //toWrite
            toWrite(key, toWrite(f))
        }

        fun toWrite(key: SelectionKey, impl: Impl?) {
            //toWrite
            key.interestOps(OP_WRITE).attach(impl)
            key.selector().wakeup()
        }


        fun toWrite(f: F): Impl {
            return object : Impl() {
                @kotlin.Throws(Exception::class)
                override fun onWrite(key: SelectionKey?) {
                    f.apply(key)
                }
            }
        }

        fun toConnect(f: F): Impl {
            //toConnect
            return object : Impl() {
                @kotlin.Throws(Exception::class)
                override fun onConnect(key: SelectionKey?) {
                    f.apply(key)
                }
            }
        }

        var selector: Selector?
            get() {
                return FSM.selector
            }
            set(selector) {
                FSM.selector = selector
            }

        fun finishRead(
            payload: ByteBuffer,
            success: Runnable
        ): Impl {
            //finishRead
            return toRead(F { key: SelectionKey ->
                if (payload.hasRemaining()) {
                    val read: Int = read(key, payload)
                    if (-1 == read) {
                        key.cancel()
                    }
                }
                if (!payload.hasRemaining()) {
                    success.run() //warning, will not remove READ_OP from interest.  you are responsible for steering the outcome
                }
            })
        }

        fun finishRead(
            key: SelectionKey, payload: ByteBuffer,
            success: Runnable
        ) {
            if (payload.hasRemaining()) toRead(key, finishRead(payload, success))
            else success.run()
        }

        fun finishWrite(
            success: Runnable,
            vararg payload: ByteBuffer?
        ): Impl {
            val cursor: ByteBuffer = std.cat(payload)

            return toWrite(F { key: SelectionKey ->
                val write: Int = write(key, cursor)
                if (-1 == write) key.cancel()
                if (!cursor.hasRemaining()) success.run()
            })
        }

        fun finishWrite(payload: ByteBuffer?, onSuccess: Runnable): Impl {
            return finishWrite(onSuccess, payload)
        }

        fun finishWrite(
            key: SelectionKey, onSuccess: Runnable,
            vararg payload: ByteBuffer?
        ) {
            toWrite(key, finishWrite(onSuccess, *payload))
        }

        fun finishRead(payload: ByteBuffer, success: F): Impl {
            return toRead(F { key: SelectionKey ->
                if (payload.hasRemaining()) {
                    val read: Int = read(key, payload)
                    if (-1 == read) key.cancel()
                }
                if (!payload.hasRemaining()) success.apply(key) //warning, will not remove READ_OP from interest.  you are responsible for steering the outcome
            })
        }

        fun finishRead(
            key: SelectionKey, payload: ByteBuffer,
            success: F
        ) {
            log(key, "finishRead")
            if (!payload.hasRemaining()) {
                try {
                    success.apply(key)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                toRead(key, finishRead(payload, success))
            }
        }

        fun finishWrite(
            success: F,
            vararg src1: ByteBuffer?
        ): Impl {
            log(success, "finishWrite")
            val src: ByteBuffer = std.cat(src1)
            return toWrite(F { key: SelectionKey ->
                val write: Int = write(key, src)
                if (-1 == write) key.cancel()
                if (!src.hasRemaining()) success.apply(key)
            })
        }

        fun finishWrite(payload: ByteBuffer?, onSuccess: F) {
            finishWrite(onSuccess, payload)
        }

        fun finishWrite(
            key: SelectionKey, onSuccess: F,
            vararg payload: ByteBuffer?
        ) {
            log(onSuccess, "finishWrite-pre")
            val cursor: ByteBuffer = std.cat(payload)
            try {
                val channel: SocketChannel = key.channel() as SocketChannel
                if (cursor.hasRemaining()) channel.write(cursor)
                if (cursor.hasRemaining()) toWrite(key, finishWrite(onSuccess, cursor))
                else try {
                    onSuccess.apply(key)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        @kotlin.Throws(Exception::class)
        fun read(channel: Channel, fromNet: ByteBuffer?): Int {
            return read((channel as SocketChannel).keyFor(selector), fromNet)
        }

        @kotlin.Throws(Exception::class)
        fun write(channel: Channel, fromApp: ByteBuffer?): Int {
            return write((channel as SocketChannel).keyFor(selector), fromApp)
        }

        @kotlin.Throws(Exception::class)
        fun write(key: SelectionKey, src: ByteBuffer): Int {
            val sslEngine: SSLEngine? = FSM.sslState.get(key)
            var write: Int
            if (null == sslEngine) {
                return (key.channel() as SocketChannel).write(src)
            }
            val toNet: ByteBuffer = sslBacklog.toNet.resume(pair(key, sslEngine))
            val fromApp: ByteBuffer = sslBacklog.fromApp.resume(pair(key, sslEngine))
            val origin: ByteBuffer = src.duplicate()
            push(src, fromApp)
            val wrap: SSLEngineResult = sslEngine.wrap(fromApp.flip() as ByteBuffer?, toNet)
            fromApp.compact()
            log("write:wrap: " + wrap)


            when (wrap.getHandshakeStatus()) {
                NOT_HANDSHAKING, FINISHED -> {
                    val status: Status = wrap.getStatus()
                    when (status) {
                        BUFFER_OVERFLOW, OK -> {
                            val channel: SocketChannel = key.channel() as SocketChannel
                            val ignored: Int = channel.write(toNet.flip() as ByteBuffer?)
                            toNet.compact()
                            return src.position() - origin.position()
                        }

                        CLOSED -> {
                            key.cancel()
                            return -1
                        }
                    }
                }

                NEED_TASK, NEED_WRAP, NEED_UNWRAP -> sslPush(key, sslEngine)
            }
            return 0
        }

        @kotlin.Throws(Exception::class)
        fun read(key: SelectionKey, toApp: ByteBuffer): Int {
            val sslEngine: SSLEngine? = FSM.sslState.get(key)
            val read: Int
            if (null == sslEngine) {
                return (key.channel() as SocketChannel).read(toApp)
            }
            val fromNet: ByteBuffer = sslBacklog.fromNet.resume(pair(key, sslEngine))
            read = (key.channel() as SocketChannel).read(fromNet)
            val overflow: ByteBuffer = sslBacklog.toApp.resume(pair(key, sslEngine))
            val origin: ByteBuffer = toApp.duplicate()
            val unwrap: SSLEngineResult = sslEngine.unwrap(bb(fromNet, flip), overflow)
            push(bb(overflow, flip), toApp)
            if (overflow.hasRemaining()) log(("**!!!* sslBacklog.toApp retaining " + overflow.remaining()).toString() + " bytes")
            overflow.compact()
            fromNet.compact()
            log("read:unwrap: " + unwrap)
            val status: Status = unwrap.getStatus()
            when (unwrap.getHandshakeStatus()) {
                NOT_HANDSHAKING, FINISHED -> when (status) {
                    BUFFER_UNDERFLOW -> {
                        if (-1 == read) key.cancel()

                        return toApp.position() - origin.position()
                    }

                    OK -> return toApp.position() - origin.position()

                    CLOSED -> {
                        key.cancel()
                        return -1
                    }
                }

                NEED_TASK, NEED_WRAP, NEED_UNWRAP -> sslPush(key, sslEngine)
            }

            return 0
        }

        fun sslPop(key: SelectionKey) {
            val remove: Pair<Integer, Object> = FSM.sslGoal.remove(key)
            key.interestOps(remove.getA()).attach(remove.getB())
            key.selector().wakeup()
        }

        @kotlin.Throws(Exception::class)
        fun sslPush(key: SelectionKey, engine: SSLEngine?) {
            log(key, "sslPush")
            FSM.sslGoal.put(key, pair(key.interestOps(), key.attachment()))
            FSM.handShake(pair(key, engine))
        }

        /**
         * like finishWrite however does not coalesce buffers together
         *
         * @param key
         * @param success
         * @param payload
         */
        fun finishWriteSeq(key: SelectionKey, success: Runnable, vararg payload: ByteBuffer?) {
            finishWriteSeq(
                key,
                F { key1: SelectionKey? -> success.run() }, *payload
            )
        }

        /**
         * like finishWrite however does not coalesce buffers together
         *
         * @param key
         * @param success
         * @param payload
         */
        fun finishWriteSeq(key: SelectionKey, success: F, vararg payload: ByteBuffer?) {
            log(key, "finishWriteSeq")
            key.interestOps(OP_WRITE).attach(toWrite(object : F {
                var c: Int = 0

                @kotlin.Throws(Exception::class)
                override fun apply(key1: SelectionKey) {
                    while (true) {
                        val cursor: ByteBuffer? = payload.get(c)
                        val write: Int = write(key1, cursor)
                        if (-1 == write) {
                            key1.cancel()
                            return
                        }
                        if (cursor.hasRemaining()) return
                        payload.get(c) = null
                        c++
                        if (payload.size == c) {
                            success.apply(key)
                            return
                        }
                    }
                }
            }))
            key.selector().wakeup()
        }

        @kotlin.Throws(IOException::class)
        fun toConnect(socketAddress: InetSocketAddress?, f: F) {
            val open: SocketChannel = SocketChannel.open()
            open.configureBlocking(false)
            open.connect(socketAddress)
            val register: SelectionKey = open.register(selector, OP_CONNECT)
            toConnect(register, f)
        }

        /**
         * makes a key/channel go buh-bye.
         */
        fun bye(key: SelectionKey) {
            log(key, "bye")
            try {
                try {
//          SSLEngine sslEngine = FSM.sslState.get(key);
//          if (null != sslEngine) {
////            log(sslEngine.isInboundDone(), "sslEngine.isInboundDone()");
////            log(sslEngine.isOutboundDone(), "sslEngine.isOutboundDone()");
////            Pair<SelectionKey, SSLEngine> pair = pair(key, sslEngine);
////            log(sslBacklog.toApp.resume(pair).toString(), "sslBacklog.toApp");
////            log(sslBacklog.fromNet.resume(pair).toString(), "sslBacklog.fromNet");
////            log(sslBacklog.toNet.resume(pair).toString(), "sslBacklog.toNet");
////            log(sslBacklog.fromApp.resume(pair).toString(), "sslBacklog.fromApp");
////            sslEngine.closeInbound();
////            sslEngine.closeOutbound();
////            FSM.needWrap(pair( key, sslEngine));
//          }
                    key.cancel()
                } catch (ignored: Throwable) {
                }
                key.channel().close()
            } catch (ignored: Throwable) {
            }
        }

        @kotlin.Throws(Exception::class)
        fun park(key: SelectionKey, then: F) {
            key.interestOps(0)
            then.apply(key)
        }

        fun <T : F?> park(vararg then: F): T {
            return F { key: SelectionKey ->
                key.interestOps(0)
                if (then.size > 0) {
                    then.get(0).apply(key)
                }
            } as F as T
        }

        fun terminate(vararg then: F): F {
            log(if (then.size > 0) then.get(0) else "-", "terminate")
            return F { deadKeyWalking: SelectionKey ->
                bye(deadKeyWalking)
                if (then.size > 0) {
                    val f: F = then.get(0)
                    f.apply(deadKeyWalking)
                }
            }
        }

        /**
         * barriers and locks sometimes want thier own thread.
         *
         * @param onSuccess
         * @return
         */
        fun isolate(onSuccess: F): F {
            return F { key: SelectionKey? ->
                FSM.executorService.submit(Callable<Void> {
                    onSuccess.apply(key)
                    null
                } as Callable<Void?>)
            }
        }

        interface F {
            @kotlin.Throws(Exception::class)
            fun apply(key: SelectionKey?)
        }
    }

    class Impl : AsioVisitor {
        init {
            if (`$DBG`) `$origins`.put(this, wheresWaldo(4))
        }

        @kotlin.Throws(Exception::class)
        override fun onRead(key: SelectionKey) {
            System.err.println("fail: " + key.toString())
            val receiveBufferSize: Int = 4 shl 10
            val trim: String = UTF_8.decode(
                ByteBuffer.allocateDirect(receiveBufferSize)
            ).toString()
                .trim()

            throw UnsupportedOperationException(
                ("found " + trim + " in "
                        + getClass().getCanonicalName())
            )
        }

        /**
         * this doesn't change very often for outbound web connections
         *
         * @param key
         * @throws Exception
         */
        @kotlin.Throws(Exception::class)
        override fun onConnect(key: SelectionKey) {
            if ((key.channel() as SocketChannel).finishConnect()) key.interestOps(OP_WRITE)
        }

        @kotlin.Throws(Exception::class)
        override fun onWrite(key: SelectionKey) {
            val channel: SocketChannel = key.channel() as SocketChannel
            System.err.println(
                "buffer underrun?: "
                        + channel.socket().getRemoteSocketAddress()
            )
            throw UnsupportedOperationException(
                "found in "
                        + getClass().getCanonicalName()
            )
        }

        /**
         * HIGHLY unlikely to solve a problem with OP_READ | OP_WRITE,
         * each network socket protocol typically requires one or the other but not both.
         *
         * @param key the serversocket key
         * @throws Exception
         */
        @kotlin.Throws(Exception::class)
        override fun onAccept(key: SelectionKey) {
            val c: ServerSocketChannel = key.channel() as ServerSocketChannel
            val accept: SocketChannel = c.accept()
            accept.configureBlocking(false)
            accept.register(
                key.selector(), OP_READ or OP_WRITE, key
                    .attachment()
            )
        }

        companion object {
            /**
             * tracking aid
             *
             * @param depth typically 2 is correct
             * @return a stack trace string that intellij can hyperlink
             */
            fun wheresWaldo(vararg depth: Int): String {
                val d: Int = if (0 < depth.size) depth.get(0) else 2
                val throwable: Throwable = Throwable()
                val throwable1: Throwable = throwable.fillInStackTrace()
                val stackTrace: Array<StackTraceElement> = throwable1.getStackTrace()
                val ret: StringBuilder = StringBuilder()
                var i: Int = 2
                val end: Int = min(stackTrace.size - 1, d)
                while (i <= end) {
                    val stackTraceElement: StackTraceElement = stackTrace.get(i)
                    ret.append("\tat ").append(stackTraceElement.getClassName())
                        .append(".").append(stackTraceElement.getMethodName())
                        .append("(").append(stackTraceElement.getFileName())
                        .append(":").append(stackTraceElement.getLineNumber())
                        .append(")\n")

                    i++
                }
                return ret.toString()
            }
        }
    }

    companion object {
        val `$DBG`: Boolean = "true".equals(Config.get("DEBUG_VISITOR_ORIGINS", "false"))
        val `$origins`: Map<Impl, String>? = if (`$DBG`)
            WeakHashMap()
        else
            null
    }
}
