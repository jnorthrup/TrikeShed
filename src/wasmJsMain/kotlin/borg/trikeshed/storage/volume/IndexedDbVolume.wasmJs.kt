package borg.trikeshed.storage.volume

import kotlin.js.Promise
import kotlinx.coroutines.await

class IndexedDbVolume : Volume {
    override val blockSize: Int = 4096
    override val capacity: Long = 1024L * 1024L * 100L

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val lbaStr = lba.toString()
        val result = try { readIdb(lbaStr, count).await<JsAny?>() } catch (e: Throwable) { null }
        return jsInt8ArrayToByteArray(result, count)
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val lbaStr = lba.toString()
        val ext = byteArrayToJsInt8Array(data)
        try { writeIdb(lbaStr, ext).await<JsAny?>() } catch (e: Throwable) { }
    }

    override suspend fun sync() { }
}

class OpfsVolume : Volume {
    override val blockSize: Int = 4096
    override val capacity: Long = 1024L * 1024L * 100L

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val result = try { readOpfs(lba.toDouble(), count).await<JsAny?>() } catch (e: Throwable) { null }
        return jsInt8ArrayToByteArray(result, count)
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val ext = byteArrayToJsInt8Array(data)
        try { writeOpfs(lba.toDouble(), ext).await<JsAny?>() } catch (e: Throwable) { }
    }

    override suspend fun sync() { }
}

// -------------------------------------------------------------
// WasmJs interop layer

@JsFun("""
(lbaStr, count) => {
    return new Promise((resolve, reject) => {
        const idb = globalThis.indexedDB || globalThis.mozIndexedDB || globalThis.webkitIndexedDB || globalThis.msIndexedDB;
        if (!idb) { resolve(null); return; }

        const req = idb.open("TrikeshedVolumeDB", 1);
        req.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('blocks')) db.createObjectStore('blocks');
        };
        req.onsuccess = (e) => {
            const db = e.target.result;
            if(!db.objectStoreNames.contains('blocks')) { resolve(null); return; }
            try {
                const tx = db.transaction(['blocks'], 'readonly');
                const st = tx.objectStore('blocks');
                const getReq = st.get(lbaStr);
                getReq.onsuccess = (ev) => resolve(ev.target.result);
                getReq.onerror = () => resolve(null);
            } catch (e) { resolve(null); }
        };
        req.onerror = () => resolve(null);
    });
}
""")
private external fun readIdb(lbaStr: String, count: Int): Promise<JsAny?>

@JsFun("""
(lbaStr, data) => {
    return new Promise((resolve, reject) => {
        const idb = globalThis.indexedDB || globalThis.mozIndexedDB || globalThis.webkitIndexedDB || globalThis.msIndexedDB;
        if (!idb) { resolve(null); return; }

        const req = idb.open("TrikeshedVolumeDB", 1);
        req.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('blocks')) db.createObjectStore('blocks');
        };
        req.onsuccess = (e) => {
            const db = e.target.result;
            if(!db.objectStoreNames.contains('blocks')) { resolve(null); return; }
            try {
                const tx = db.transaction(['blocks'], 'readwrite');
                tx.oncomplete = () => resolve(null);
                tx.onerror = () => resolve(null);
                const st = tx.objectStore('blocks');
                st.put(data, lbaStr);
            } catch(e) { resolve(null); }
        };
        req.onerror = () => resolve(null);
    });
}
""")
private external fun writeIdb(lbaStr: String, data: JsAny): Promise<JsAny?>

@JsFun("""
(lba, count) => {
    return new Promise((resolve, reject) => {
        const nav = globalThis.navigator;
        if (!nav || !nav.storage || !nav.storage.getDirectory) {
            resolve(null); return;
        }
        nav.storage.getDirectory().then(dir => {
            return dir.getFileHandle('trikeshed_vol', { create: true });
        }).then(handle => {
            return handle.getFile();
        }).then(file => {
            const slice = file.slice(lba, lba + count);
            return slice.arrayBuffer();
        }).then(buf => {
            resolve(new Int8Array(buf));
        }).catch(e => {
            resolve(null);
        });
    });
}
""")
private external fun readOpfs(lba: Double, count: Int): Promise<JsAny?>

@JsFun("""
(lba, data) => {
    return new Promise((resolve, reject) => {
        const nav = globalThis.navigator;
        if (!nav || !nav.storage || !nav.storage.getDirectory) {
            resolve(null); return;
        }
        nav.storage.getDirectory().then(dir => {
            return dir.getFileHandle('trikeshed_vol', { create: true });
        }).then(handle => {
            return handle.createWritable({ keepExistingData: true });
        }).then(writable => {
            writable.write({ type: 'write', position: lba, data: data.buffer || data })
            .then(() => writable.close())
            .then(() => resolve(null))
            .catch(e => resolve(null));
        }).catch(e => resolve(null));
    });
}
""")
private external fun writeOpfs(lba: Double, data: JsAny): Promise<JsAny?>

@JsFun("() => []")
private external fun createJsArray(): JsAny

@JsFun("(arr, val) => arr.push(val)")
private external fun jsArrayPush(arr: JsAny, value: Int)

@JsFun("(arr) => arr.length || (arr.byteLength ? arr.byteLength : 0)")
private external fun getJsArrayLength(arr: JsAny): Int

@JsFun("(arr, i) => arr[i] !== undefined ? (typeof arr.readInt8 === 'function' ? arr.readInt8(i) : arr[i]) : 0")
private external fun getJsArrayItem(arr: JsAny, i: Int): Int

private fun byteArrayToJsInt8Array(arr: ByteArray): JsAny {
    val jsArr = createJsArray()
    for (i in arr.indices) {
        jsArrayPush(jsArr, arr[i].toInt() and 0xFF)
    }
    return jsArr
}

private fun jsInt8ArrayToByteArray(arr: JsAny?, count: Int): ByteArray {
    val res = ByteArray(count)
    if (arr == null) return res
    val len = getJsArrayLength(arr)
    val max = if (len < count) len else count
    for(i in 0 until max) {
        val v = getJsArrayItem(arr, i)
        res[i] = v.toByte()
    }
    return res
}
