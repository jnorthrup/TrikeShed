package borg.trikeshed.lcnc

/**
 * Sub-VM lego taxonomy — the commonMain half of the module legos (the runners are
 * jvmMain [borg.trikeshed.lcnc.SubVmLegos]). The node-type prefix is the only
 * spelling both halves share: contracts declare `vm.*` types here, the daemon
 * registers `vm.*` runners there, and the concentric surface needs no bespoke
 * UI for either.
 */
object SubVm {
    /** Node-type prefix for every sub-VM lego (`vm.tika`, `vm.corenlp`, …). */
    const val LEGO_PREFIX = "vm."
}
