package org.xvm.runtime;

import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite;

/**
 * Pointcut-facing verifier for typedef production rows.
 *
 * This is deliberately not a cascade table. Cascade stays for tabular stats;
 * typedef production keeps its own rows because parameter identity is semantic,
 * not a histogram bucket.
 */
public final class TypedefPointcutVerifier {
    private final TypedefProductionTable table;

    public TypedefPointcutVerifier(TypedefProductionTable table) {
        this.table = table;
    }

    public TypedefProductionTable.Row typedefPointcut(int opcode, TypedefCallsite site, String alias, int paramCount,
                                                      XvmPrimitiveTranslationTable.XvmPrimitive primitive) {
        return table.appendProduction(opcode, site, alias, paramCount, primitive);
    }

    public TypedefProductionTable.Report verify() {
        return table.verify();
    }
}
