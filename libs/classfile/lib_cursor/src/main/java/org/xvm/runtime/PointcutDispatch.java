package org.xvm.runtime;

/**
 * Interface for VM pointcut dispatch table.
 * Implemented by VmPointcutDispatch (lib_cursor) and testable via this interface.
 *
 * Wireproto: opcode byte = codec selector (1 byte, 0-255).
 */
public interface PointcutDispatch {

    int TABLE_SIZE = 256;

    enum Kind {
        GAP,    // unclassified / catch-all
        CALL,   // direct call / virtual invoke
        ALLOC,  // object allocation / construction
        RETURN, // return site
        FIELD,  // property get/set
        TYPE,   // type/copy/cast
        ASSERT, // assertion
        LOOP,   // control flow
        SYNC    // synchronization
    }

    Kind kindOf(int opcode);

    boolean hasBefore(int opcode);

    boolean hasAfter(int opcode);
}