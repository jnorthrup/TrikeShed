package org.xvm.cursor

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

// ThreadLocal ContextStaircase — 3 levels, flatten, reset
object CS {
    private val st = ThreadLocal.withInitial { ArrayDeque<Ctx>(3) }
    data class Ctx(val layer: Int, val entry: Long, val op: Int, val nano: Long, val role: OverlayRole)
    fun push(e: Long, o: Int, n: Long, r: OverlayRole) = st.get().addLast(Ctx(st.get().size.coerceAtMost(2), e, o, n, r))
    fun pop(): Ctx? { val d = st.get(); if(d.isEmpty()) return null; val c = d.removeLast(); if(d.size==2) d.clear().also{ d.addLast(Ctx(-1,0,-1,System.nanoTime(),OverlayRole.AGGREGATE)) }; return c }
    fun peek() = st.get().lastOrNull()
    fun depth() = st.get().size
    fun flat() = st.get().let{ if(it.size==3) Ctx(-1,0,-1,it[2].nano,OverlayRole.AGGREGATE) else null }
}

// ReduxEvent with ThreadLocal context staircasing
data class RE<T>(val nano: Long, val entry: Long, val op: Int, val v: T?, val role: OverlayRole)

// Async task — surgical
abstract class GT<T>(val e: Long, val o: Int, val n: Long, val r: OverlayRole = OverlayRole.OBSERVATION) : CoroutineScope {
    private val sup = SupervisorJob()
    override val coroutineContext get() = sup + Dispatchers.Default
    val stack get() = CS.depth()
    abstract suspend fun go(): T
    suspend fun exec(): T = CS.push(e,o,n,r).let{ try{go()}finally{CS.pop()} }
}

// Redux chain
class RC {
    private val q = ConcurrentLinkedQueue<GT<*>>()
    fun <T : Any> enqueue(t: GT<T>) {
        q.add(t)
        GlobalScope.async { t.exec() }
    }
    suspend fun run() { var d=0; while(!q.isEmpty()){ q.poll()?.exec(); if(++d%3==0) CS.flat()?.let{notify(RE(it.nano,it.entry,-1,null,it.role))} } }
    private val subs = ConcurrentHashMap<Int,(RE<*>)->Unit>()
    fun sub(fn: (RE<*>)->Unit): Int {
        val keys = subs.keys.toList()
        val id = if (keys.isEmpty()) 0 else keys.maxOrNull()!! + 1
        subs[id] = fn
        return id
    }
    fun unsub(id: Int) = subs.remove(id)
    private fun notify(e: RE<*>) = subs.values.forEach{ it(e) }
}

// Main entry point — gradle task target
fun main() {
    println("P-code schema loader")
    println("Dump: /Users/jim/work/xvm/lib_cursor/typeconstant-pcode.json")
    val rc = RC()
    val subId = rc.sub { println("RE: nanos=${it.nano}, role=${it.role}") }
    println("Subscribed: $subId")
}
