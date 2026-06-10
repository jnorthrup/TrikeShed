package org.xvm.asm.constants;

import org.xvm.asm.Constant;

/**
 * Typedef resolution event publisher — pure-Java cold WAL producer.
 *
 * <p>Call sites call {@link #record} to capture resolveTypedefs() outcomes.
 * Kotlin (TypedefResolutionSeries) is invoked via Class.forName + reflection at
 * runtime — no compile-time Kotlin dependency in javatools.
 *
 * <p>JournalSeries (WAL) is the write path. The Kotlin side drains the ring
 * and feeds into ReduxMutableSeries (cold state). A MetaSeries (RowVec) is
 * lazily derived from Redux state on demand.
 *
 * <p>Blackboard context is the ambient ConstantPool.
 * Activate with: {@code TypedefResolutionPublisher.active = true;}
 */
public final class TypedefResolutionPublisher {

    private static final String KOTLIN_CLASS = "org.xvm.cursor.TypedefResolutionSeries";
    private static final String RECORD_METHOD = "record";
    private static final String REVERT_METHOD = "revert";
    private static final String REVERT_SITE_METHOD = "revertSite";
    private static final String REVERT_POOL_METHOD = "revertPool";
    private static final String JOURNAL_METHOD = "journal";
    private static final String DRAIN_METHOD = "drain";
    private static final String SIZE_METHOD = "size";
    private static final String META_METHOD = "metaSeries";
    private static final String TO_ROWVEC_METHOD = "toRowVec";

    /** Gate — no allocation in hot path when disabled */
    public static volatile boolean active = false;

    /** Cached Kotlin availability — resolved once on first record() */
    private static volatile Boolean kotlinAvailable = null;

    /** Cached Kotlin instance (singleton object) */
    private static volatile Object kotlinInstance = null;

    /** Cached method handles */
    private static java.lang.reflect.Method recordMethod = null;
    private static java.lang.reflect.Method revertMethod = null;
    private static java.lang.reflect.Method revertSiteMethod = null;
    private static java.lang.reflect.Method revertPoolMethod = null;
    private static java.lang.reflect.Method journalMethod = null;
    private static java.lang.reflect.Method drainMethod = null;
    private static java.lang.reflect.Method sizeMethod = null;
    private static java.lang.reflect.Method metaSeriesMethod = null;
    private static java.lang.reflect.Method toRowVecMethod = null;

    // ── Callsite tags ─────────────────────────────────────────────────────────

    /**
     * All known resolveTypedefs() call sites in javatools ASM.
     * Each entry is a (file, line, description) named tuple.
     */
    public enum TypedefCallsite {
        // ── org.xvm.asm ──────────────────────────────────────────────────────
        CP_EnsureConstant("ConstantPool.java", 177, "ensuresConstant resolveTypedefs"),
        Param_TypeParam("Parameter.java", 164, "typeParam.resolveTypedefs"),
        CS_FnThis("ClassStructure.java", 3222, "fnThis.resolveTypedefs"),
        MS_IdNew("MethodStructure.java", 396, "idOld.resolveTypedefs"),
        Comp_Id("Component.java", 144, "constId.resolveTypedefs"),
        Comp_TypeContrib("Component.java", 3220, "m_typeContrib.resolveTypedefs"),
        TC_CombineThis("TypeConstant.java", 900, "this.resolveTypedefs in combine"),
        TC_CombineThat("TypeConstant.java", 901, "that.resolveTypedefs in combine"),
        TC_UnionThis("TypeConstant.java", 1002, "this.resolveTypedefs in union"),
        TC_UnionThat("TypeConstant.java", 1003, "that.resolveTypedefs in union"),
        TC_AndNotThis("TypeConstant.java", 1066, "this.resolveTypedefs in andNot"),
        TC_AndNotThat("TypeConstant.java", 1067, "that.resolveTypedefs in andNot"),
        TC_ResolveGenerics("TypeConstant.java", 1114, "constOriginal.resolveTypedefs"),
        TC_AssertRight("TypeConstant.java", 5544, "typeRight.resolveTypedefs in assert"),
        ATC_Underlying("AnnotatedTypeConstant.java", 291, "getUnderlyingType.resolveTypedefs"),
        ATC_ConstType("AnnotatedTypeConstant.java", 698, "m_constType.resolveTypedefs"),
        AC_Type("ArrayConstant.java", 195, "typeOld.resolveTypedefs"),
        AC_Element("ArrayConstant.java", 202, "constOld.resolveTypedefs"),
        EVC_Class("EnumValueConstant.java", 90, "constOld.resolveTypedefs"),
        ADTC_Parent("AbstractDependantTypeConstant.java", 44, "typeParent.resolveTypedefs"),
        ADTC_Original("AbstractDependantTypeConstant.java", 139, "typeOriginal.resolveTypedefs"),
        SC_Id("SingletonConstant.java", 161, "constOld.resolveTypedefs"),
        UTC_Type("UnresolvedTypeConstant.java", 79, "type.resolveTypedefs"),
        UTC_Resolve("UnresolvedTypeConstant.java", 282, "m_type.resolveTypedefs"),
        UNC_Id("UnresolvedNameConstant.java", 168, "m_constId.resolveTypedefs"),
        MC_Type("MapConstant.java", 221, "typeOld.resolveTypedefs"),
        MC_Key("MapConstant.java", 228, "constOldKey.resolveTypedefs"),
        MC_Val("MapConstant.java", 242, "constOldVal.resolveTypedefs"),
        ITC_Types1_208("IntersectionTypeConstant.java", 208, "m_constType1.resolveTypedefs"),
        ITC_Types1_209("IntersectionTypeConstant.java", 209, "m_constType2.resolveTypedefs"),
        ITC_Types2_215("IntersectionTypeConstant.java", 215, "m_constType1.resolveTypedefs 215"),
        ITC_Types2_216("IntersectionTypeConstant.java", 216, "m_constType2.resolveTypedefs 216"),
        ITC_Actual("IntersectionTypeConstant.java", 359, "typeActual.resolveTypedefs"),
        UTC_IsUnion1("UnionTypeConstant.java", 120, "type.resolveTypedefs isUnion check 1"),
        UTC_IsUnion2("UnionTypeConstant.java", 145, "type.resolveTypedefs isUnion check 2"),
        UTC_Types1("UnionTypeConstant.java", 229, "m_constType1.resolveTypedefs"),
        UTC_Types2("UnionTypeConstant.java", 230, "m_constType2.resolveTypedefs"),
        UTC_Types_240("UnionTypeConstant.java", 240, "m_constType1/2.resolveTypedefs 240"),
        UTC_Types_241("UnionTypeConstant.java", 241, "m_constType1/2.resolveTypedefs 241"),
        UTC_Underlying1("UnionTypeConstant.java", 255, "getUnderlyingType.resolveTypedefs"),
        UTC_Underlying2("UnionTypeConstant.java", 256, "getUnderlyingType2.resolveTypedefs"),
        UTC_Actual("UnionTypeConstant.java", 488, "typeActual.resolveTypedefs"),
        UTC_IsUnion3("UnionTypeConstant.java", 881, "type.resolveTypedefs isUnion check 3"),
        ATC_Type("AccessTypeConstant.java", 280, "m_constType.resolveTypedefs"),
        DTC_Types1("DifferenceTypeConstant.java", 179, "m_constType1.resolveTypedefs"),
        DTC_Types2("DifferenceTypeConstant.java", 180, "m_constType2.resolveTypedefs"),
        DTC_Actual("DifferenceTypeConstant.java", 237, "typeActual.resolveTypedefs"),
        TTC_Typedef("TerminalTypeConstant.java", 525, "getReferredToType.resolveTypedefs"),
        MTC_Parent("MethodConstant.java", 578, "idOldParent.resolveTypedefs"),
        MTC_Sig("MethodConstant.java", 581, "sigOld.resolveTypedefs"),
        PTC_Type("ParameterizedTypeConstant.java", 254, "constOriginal.resolveTypedefs"),
        PTC_Param("ParameterizedTypeConstant.java", 267, "constParamOriginal.resolveTypedefs"),
        PTC_Actual("ParameterizedTypeConstant.java", 608, "typeActual.resolveTypedefs"),
        RTC_Type1("RelationalTypeConstant.java", 219, "constOriginal1.resolveTypedefs"),
        RTC_Type2("RelationalTypeConstant.java", 220, "constOriginal2.resolveTypedefs"),
        RTC_Actual("RelationalTypeConstant.java", 383, "typeActual.resolveTypedefs"),
        RC_Val1("RangeConstant.java", 296, "constOld1.resolveTypedefs"),
        RC_Val2("RangeConstant.java", 297, "constOld2.resolveTypedefs"),
        MAC_Type("MatchAnyConstant.java", 97, "constOld.resolveTypedefs"),
        Reg_Type1("Register.java", 31, "type.resolveTypedefs 1"),
        Reg_Type2("Register.java", 60, "type.resolveTypedefs 2"),
        TC_Typedef("TypedefConstant.java", 93, "typeReferred.resolveTypedefs"),
        // ── org.xvm.compiler.ast ──────────────────────────────────────────────
        TCS_PropType("TypeCompositionStatement.java", 2128, "prop.getType.resolveTypedefs"),
        IE_ArgType1("InvocationExpression.java", 469, "argMethod.getType.resolveTypedefs 1"),
        IE_ArgType2("InvocationExpression.java", 951, "argMethod.getType.resolveTypedefs 2"),
        IE_FnType1("InvocationExpression.java", 1506, "argFn.getType.resolveTypedefs 1"),
        IE_FnType2("InvocationExpression.java", 2679, "typeFn.resolveTypedefs 2"),
        LE_IsUnion1("LambdaExpression.java", 363, "typeRequired.resolveTypedefs isUnion 1"),
        LE_ReqFnType("LambdaExpression.java", 450, "typeRequired.resolveTypedefs reqFnType"),
        LE_TypeParamAnno("LambdaExpression.java", 1155, "atypeParams[i].resolveTypedefs"),
        LE_IsUnion2("ListExpression.java", 341, "typeRequired.resolveTypedefs isUnion 2"),
        AIC_ExprType("AnonInnerClass.java", 171, "exprType.ensureTypeConstant.resolveTypedefs"),
        MDS_Resolve("MethodDeclarationStatement.java", 565, "method.resolveTypedefs"),
        ;

        private final String file;
        private final int line;
        private final String description;

        TypedefCallsite(String file, int line, String description) {
            this.file = file;
            this.line = line;
            this.description = description;
        }

        /** source file name */
        public String file() { return file; }
        /** line number in source */
        public int line() { return line; }
        /** human-readable description */
        public String description() { return description; }
        /** 0-based site index */
        public int siteIndex() { return ((Enum<?>) this).ordinal(); }
    }

    // ── Publishing API ────────────────────────────────────────────────────────

    /**
     * C — Record one cold typedef resolution event.
     *
     * <p>Pool identity is System.identityHashCode(pool). The pool is the blackboard
     * parent and provides FileStructure → Module identity.
     *
     * <p>The Object[] fact written to the WAL ring:
     *   [0] factId     — long (journal index)
     *   [1] nano       — long (System.nanoTime())
     *   [2] poolId     — int (identityHashCode)
     *   [3] siteOrd    — int (TypedefCallsite.ordinal)
     *   [4] clsName    — String (interned by StringPool in Kotlin)
     *   [5] format     — String (Constant.Format.name(), interned)
     *   [6] success    — boolean
     *   [7] isReverted — boolean (false = live fact)
     *
     * @param pool     the ambient ConstantPool (blackboard parent), may be null
     * @param site     which call site (TypedefCallsite enum)
     * @param resolved the resulting TypeConstant after resolveTypedefs()
     * @param success  true if resolution completed without error
     * @return factId assigned (for later revert), or -1 if not active/unavailable
     */
    public static long record(org.xvm.asm.ConstantPool pool, TypedefCallsite site,
            TypeConstant resolved, boolean success) {
        if (!active) return -1L;

        Boolean avail = kotlinAvailable;
        if (avail == null) {
            kotlinAvailable = checkKotlinAvailable();
        }
        if (!kotlinAvailable) return -1L;

        int poolId = System.identityHashCode(pool);
        String className = resolved.getClass().getSimpleName();
        String typeFormat = resolved.getFormat().name();

        // Intern strings into StringPool lazily
        StringPoolHelper.intern(className);
        StringPoolHelper.intern(typeFormat);
        // Intern module name lazily
        String moduleName = getModuleName(pool);
        if (moduleName != null) StringPoolHelper.intern(moduleName);

        try {
            Object instance = getKotlinInstance();
            Object result = recordMethod.invoke(instance,
                poolId, site.siteIndex(), className, typeFormat, success);
            return (result == null) ? -1L : ((Number) result).longValue();
        } catch (Throwable t) {
            kotlinAvailable = false;
            return -1L;
        }
    }

    /**
     * Convenience overload — omit the pool.
     */
    public static long record(TypedefCallsite site, TypeConstant resolved, boolean success) {
        return record(null, site, resolved, success);
    }

    // ── WAL operations ────────────────────────────────────────────────────────

    /**
     * Dump WAL — force a ring flush and return the JournalSeries as a debug string.
     * Returns empty string if Kotlin unavailable.
     */
    public static String dumpWal() {
        try {
            Object instance = getKotlinInstance();
            Object journal = journalMethod.invoke(instance);
            if (journal == null) return "";
            return String.valueOf(journal);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Drain count — how many events currently in the WAL ring.
     */
    public static int drainDepth() {
        try {
            Object instance = getKotlinInstance();
            Object result = drainMethod.invoke(instance);
            return (result == null) ? 0 : ((Number) result).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── Redux state queries ────────────────────────────────────────────────────

    /**
     * R — Revert a specific fact by its factId.
     * Emits a compensating fact into the WAL.
     * @return true if the fact existed and was reverted
     */
    public static boolean revert(long factId) {
        try {
            Object instance = getKotlinInstance();
            return (boolean) revertMethod.invoke(instance, factId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * R — Revert all facts for a (poolId, siteOrdinal) pair.
     * @return number of facts reverted
     */
    public static int revertSite(int poolId, int siteOrdinal) {
        try {
            Object instance = getKotlinInstance();
            return (int) revertSiteMethod.invoke(instance, poolId, siteOrdinal);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * R — Revert all facts for a pool.
     * @return number of facts reverted
     */
    public static int revertPool(int poolId) {
        try {
            Object instance = getKotlinInstance();
            return (int) revertPoolMethod.invoke(instance, poolId);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Total live facts in Redux state.
     */
    public static int size() {
        try {
            Object instance = getKotlinInstance();
            return (int) sizeMethod.invoke(instance);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Get a fact as JSON string by factId.
     */
    public static String factAsJson(long factId) {
        try {
            Object instance = getKotlinInstance();
            Object fact = instance.getClass().getMethod("fact", long.class).invoke(instance, factId);
            return (fact == null) ? "" : String.valueOf(fact);
        } catch (Throwable t) {
            return "";
        }
    }

    // ── RowVec / MetaSeries ───────────────────────────────────────────────────

    /**
     * Get the MetaSeries (derived from Redux state) as a RowVec string.
     * Returns "keys|cells" format for Confix parsing.
     */
    public static String metaAsRowVec() {
        try {
            Object instance = getKotlinInstance();
            return (String) toRowVecMethod.invoke(instance);
        } catch (Throwable t) {
            return "";
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String getModuleName(org.xvm.asm.ConstantPool pool) {
        if (pool == null) return null;
        try {
            Object fs = pool.getClass().getMethod("getFileStructure").invoke(pool);
            if (fs == null) return null;
            Object moduleId = fs.getClass().getMethod("getModuleId").invoke(fs);
            if (moduleId == null) return null;
            return (String) moduleId.getClass().getMethod("getName").invoke(moduleId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getKotlinInstance() throws Throwable {
        Object inst = kotlinInstance;
        if (inst != null) return inst;
        Class<?> cls = Class.forName(KOTLIN_CLASS);
        inst = cls.getField("INSTANCE").get(null);
        kotlinInstance = inst;
        return inst;
    }

    private static boolean checkKotlinAvailable() {
        try {
            Class<?> cls = Class.forName(KOTLIN_CLASS);
            Object instance = cls.getField("INSTANCE").get(null);

            recordMethod = cls.getMethod(RECORD_METHOD,
                int.class, int.class, String.class, String.class, boolean.class);
            revertMethod = cls.getMethod(REVERT_METHOD, long.class);
            revertSiteMethod = cls.getMethod(REVERT_SITE_METHOD, int.class, int.class);
            revertPoolMethod = cls.getMethod(REVERT_POOL_METHOD, int.class);
            journalMethod = cls.getMethod(JOURNAL_METHOD);
            drainMethod = cls.getMethod(DRAIN_METHOD);
            sizeMethod = cls.getMethod(SIZE_METHOD);
            metaSeriesMethod = cls.getMethod(META_METHOD);
            toRowVecMethod = cls.getMethod(TO_ROWVEC_METHOD);

            kotlinInstance = instance;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Hidden constructor */
    private TypedefResolutionPublisher() { throw new UnsupportedOperationException(); }

    // ── StringPool helper (pure Java, no Kotlin dep) ─────────────────────────

    /**
     * Lightweight string intern facade — delegates into Kotlin StringPool
     * via Class.forName to avoid compile-time dep.
     */
    public static final class StringPoolHelper {
        private static volatile java.lang.reflect.Method internMethod = null;

        /** Intern a string into the Kotlin StringPool. Idempotent. */
        public static int intern(String s) {
            if (s == null) return 0;
            try {
                if (internMethod == null) {
                    Class<?> cls = Class.forName("org.xvm.cursor.StringPool");
                    internMethod = cls.getMethod("intern", String.class);
                }
                return ((Number) internMethod.invoke(null, s)).intValue();
            } catch (Throwable t) {
                return s.hashCode();
            }
        }

        private StringPoolHelper() { throw new UnsupportedOperationException(); }
    }
}
