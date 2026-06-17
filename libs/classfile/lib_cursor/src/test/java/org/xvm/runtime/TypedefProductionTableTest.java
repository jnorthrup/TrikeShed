package org.xvm.runtime;

import org.junit.jupiter.api.Test;
import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypedefProductionTableTest {

    @Test
    public void stealthTypedef_erasesWithoutIdentity() {
        var table = new TypedefProductionTable(4);

        var row = table.appendProduction(
                0x66,
                TypedefCallsite.PTC_Param,
                "JsonObject",
                0,
                XvmPrimitiveTranslationTable.XvmPrimitive.String);

        assertEquals(1, table.rowCount());
        assertEquals(TypedefProductionTable.Mode.STEALTH, row.mode());
        assertTrue(row.erased(), "stealth typedef must erase cleanly");
        assertEquals(0L, row.identityHash(), "stealth typedef carries no identity hash");
        assertEquals(VmPointcutDispatch.Kind.TYPE, row.pointcutKind());
        assertEquals(XvmPrimitiveTranslationTable.VtableLayout.INTERFACE, row.layout());
    }

    @Test
    public void parameterizedTypedef_preservesIdentity() {
        var table = new TypedefProductionTable(4);

        var row = table.appendProduction(
                0x66,
                TypedefCallsite.PTC_Param,
                "JsonObject<Element>",
                1,
                XvmPrimitiveTranslationTable.XvmPrimitive.String);

        assertEquals(TypedefProductionTable.Mode.PARAMETERIZED, row.mode());
        assertFalse(row.erased(), "parameterized typedef must not erase identity");
        assertTrue(row.identityHash() != 0L, "parameterized typedef needs stable identity hash");
        assertEquals(row.identityHash(), table.row(0).identityHash());
    }

    @Test
    public void verifier_acceptsPointcutsIntoProductionTable() {
        var table = new TypedefProductionTable(4);
        var verifier = new TypedefPointcutVerifier(table);

        verifier.typedefPointcut(
                0x66,
                TypedefCallsite.PTC_Param,
                "Alias<T>",
                1,
                XvmPrimitiveTranslationTable.XvmPrimitive.Int32);
        verifier.typedefPointcut(
                0x66,
                TypedefCallsite.PTC_Param,
                "Alias0",
                0,
                XvmPrimitiveTranslationTable.XvmPrimitive.Int32);

        var report = verifier.verify();
        assertEquals(2, report.total());
        assertEquals(1, report.parameterized());
        assertEquals(1, report.stealth());
        assertEquals(0, report.failures());
        assertEquals(VmPointcutDispatch.Kind.TYPE, table.row(0).pointcutKind());
    }
}
