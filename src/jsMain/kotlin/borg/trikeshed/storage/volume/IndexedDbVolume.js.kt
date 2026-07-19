package borg.trikeshed.storage.volume

import kotlin.js.Promise
import kotlinx.coroutines.await

class IndexedDbVolume : Volume {
    override val blockSize: Int = 4096
    override val capacity: Long = 1024L * 1024L * 100L

    private var initialized = false
    private var db: dynamic = null

    private suspend fun ensureInit() {
        if (initialized) return
        val idb = js("globalThis.indexedDB || globalThis.mozIndexedDB || globalThis.webkitIndexedDB || globalThis.msIndexedDB")
        if (idb == null || idb == undefined) {
            initialized = true
            return
        }

        val promise: Promise<dynamic> = js("""
            new Promise((resolve, reject) => {
                const request = idb.open("TrikeshedVolumeDB", 1);
                request.onupgradeneeded = (event) => {
                    const db = event.target.result;
                    if (!db.objectStoreNames.contains('blocks')) {
                        db.createObjectStore('blocks');
                    }
                };
                request.onsuccess = (event) => resolve(event.target.result);
                request.onerror = (event) => reject(event.target.error);
            })
        """)

        db = try { promise.await() } catch(e: Throwable) { null }
        initialized = true
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        ensureInit()
        if (db == null) return ByteArray(count)

        val lbaStr = lba.toString()
        val promise: Promise<dynamic> = js("""
            new Promise((resolve, reject) => {
                try {
                    const transaction = this.db.transaction(['blocks'], 'readonly');
                    const store = transaction.objectStore('blocks');
                    const request = store.get(lbaStr);
                    request.onsuccess = (event) => resolve(event.target.result);
                    request.onerror = (event) => resolve(null);
                } catch(e) {
                    resolve(null);
                }
            })
        """)

        val result = try { promise.await() } catch(e: Throwable) { null }
        if (result == null || result == undefined) return ByteArray(count)

        val arr = ByteArray(count)

        val len = js("result.length") as Int? ?: 0
        val max = if (len < count) len else count
        for (i in 0 until max) {
            val v = js("result[i]")
            arr[i] = if (v != undefined) (v as Number).toByte() else 0.toByte()
        }
        return arr
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        ensureInit()
        if (db == null) return

        val lbaStr = lba.toString()

        val jsArr = js("[]")
        for(i in data.indices) {
            val byteVal = data[i].toInt() and 0xFF
            js("jsArr.push(byteVal)")
        }

        val promise: Promise<dynamic> = js("""
            new Promise((resolve, reject) => {
                try {
                    const transaction = this.db.transaction(['blocks'], 'readwrite');
                    transaction.oncomplete = () => resolve();
                    transaction.onerror = (event) => resolve();
                    const store = transaction.objectStore('blocks');
                    store.put(jsArr, lbaStr);
                } catch(e) {
                    resolve();
                }
            })
        """)
        try { promise.await() } catch(e: Throwable) { }
    }

    override suspend fun sync() {}
}

class OpfsVolume : Volume {
    override val blockSize: Int = 4096
    override val capacity: Long = 1024L * 1024L * 100L

    private var initialized = false
    private var handle: dynamic = null

    private suspend fun ensureInit() {
        if (initialized) return
        val nav = js("globalThis.navigator")
        if (nav == null || nav == undefined || nav.storage == null || nav.storage.getDirectory == null) {
            initialized = true
            return
        }

        val promise: Promise<dynamic> = js("""
            nav.storage.getDirectory()
                .then(dirHandle => dirHandle.getFileHandle('trikeshed_vol', { create: true }))
        """)

        handle = try { promise.await() } catch (e: Throwable) { null }
        initialized = true
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        ensureInit()
        if (handle == null) return ByteArray(count)

        val lbaDouble = lba.toDouble()
        val promise: Promise<dynamic> = js("""
            new Promise((resolve, reject) => {
                this.handle.getFile()
                .then(file => {
                    const slice = file.slice(lbaDouble, lbaDouble + count);
                    return slice.arrayBuffer();
                })
                .then(buffer => resolve(buffer))
                .catch(e => resolve(null));
            })
        """)
        val result = try { promise.await() } catch(e: Throwable) { null }
        if (result == null || result == undefined) return ByteArray(count)

        val arr = ByteArray(count)
        val view = js("new Int8Array(result)")
        val len = view.length as Int
        val max = if (len < count) len else count
        for (i in 0 until max) {
            val v = js("view[i]")
            arr[i] = v.unsafeCast<Byte>()
        }
        return arr
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        ensureInit()
        if (handle == null) return

        val lbaDouble = lba.toDouble()
        val jsArr = js("[]")
        for(i in data.indices) {
            val byteVal = data[i].toInt() and 0xFF
            js("jsArr.push(byteVal)")
        }
        val buffer = js("new Int8Array(jsArr).buffer")

        val promise: Promise<dynamic> = js("""
            new Promise((resolve, reject) => {
                this.handle.createWritable({ keepExistingData: true })
                .then(writable => {
                    writable.write({ type: 'write', position: lbaDouble, data: buffer })
                    .then(() => writable.close())
                    .then(() => resolve())
                    .catch(e => resolve());
                })
                .catch(e => resolve());
            })
        """)
        try { promise.await() } catch(e: Throwable) { }
    }

    override suspend fun sync() {}
}
