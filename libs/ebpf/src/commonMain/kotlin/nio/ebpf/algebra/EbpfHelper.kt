package nio.ebpf.algebra

/** eBPF helper functions */
enum class EbpfHelper(val id: Int) {
    Unspec(0), GetSmpProcId(1), KtimeGetNs(5), TracePrintk(6),
    GetPrandomU32(7), GetCgroupClassid(8), SkbVlanPush(9), SkbVlanPop(10),
    GetSocketCookie(11), MapLookupElem(100),
}
