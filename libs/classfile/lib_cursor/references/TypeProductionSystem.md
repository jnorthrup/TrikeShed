

    PART 1: THE FIVE ERASURE CUTPOINTS
    ===================================

    These are the five places where typedef identity is stripped or observed
    before it disappears. Listed in pipeline order.

    E1 — ConstantPool.register()  ConstantPool.java:177

    Gate: before resolveTypedefs() destroys the typedef reference, check if the
    incoming constant is a TerminalTypeConstant backed by a Format.Typedef
    constant whose referred-to type containsFormalType(true). If so, capture
    defConst.getPosition() as typedefPoolIdx for lib_cursor pointcut use.

    Then line 194 calls constant.resolveTypedefs() unconditionally for all
    non-null constants before the dedup lookup. Every constant entering the pool
    is typedef-erased here. Unresolved or foreign-pool constants are returned
    without registration.

    This is where parameterized typedef identity is observably last present.

    E2 — Register(TypeConstant, String, MethodStructure)  Register.java:31

    type = type.resolveTypedefs()  at line 31, before m_type is stored.
    Every register allocated for an unknown-index slot stores an already-erased type.
    Null type rejected with IllegalArgumentException.

    E3 — Register(TypeConstant, String, int)  Register.java:60

    type = type.resolveTypedefs()  at line 60, same rule for indexed registers.
    Null only allowed for A_DEFAULT / A_IGNORE / A_IGNORE_ASYNC special indices.

    E4 — TypedefConstant.getReferredToType()  TypedefConstant.java:62-93

    Called on first access. Runs a visitor over the underlying type tree:
    if an UnresolvedTypeConstant isSingleDefiningConstant and that constant IS
    this TypedefConstant, wrap it in RecursiveTypeConstant and markRecursive().
    m_fInitialized is set true once containsUnresolved() is false.
    Always returns typeReferred.resolveTypedefs() — typedef chain is chased to
    non-typedef terminal.

    This is the recursive-typedef detection gate. Cycles become
    RecursiveTypeConstant; everything else is eagerly flattened.

    E5 — TerminalTypeConstant.resolveTypedefs()  TerminalTypeConstant.java:522

    If ensureResolvedConstant().getFormat() == Format.Typedef:
      return ((TypedefConstant) constId).getReferredToType().resolveTypedefs()
    else: return this.

    This is the leaf unwrapping step called by every TypeConstant.resolveTypedefs()
    traversal on a terminal node. It is the innermost recursion of the chain chase.

    NOTE: Frame.checkType() (Frame.java:861) and Frame.resolveType() (Frame.java:1471)
    operate AFTER erasure. checkType() never sees TypedefConstant — it calls
    calculateRelation() on already-resolved TypeConstants. resolveType() handles
    formal generics (resolveGenerics) and auto-narrowing (resolveAutoNarrowing)
    but not typedef resolution.



    PART 2: OBSERVABILITY LAYER — TypedefResolutionPublisher
    =========================================================

    File: javatools/.../asm/constants/TypedefResolutionPublisher.java (406 lines)

    Master gate: public static volatile boolean active = false.
    Zero overhead when false — the entire hot path is a single volatile read.

    TypedefCallsite enum: 73 enum values, each tagging one call site across the
    javatools ASM/compiler layer with (file, line, description). The full set:
      CP_EnsureConstant (ConstantPool.java:177)
      Reg_Type1 (Register.java:31)
      Reg_Type2 (Register.java:60)
      TC_Typedef (TypedefConstant.java:93)
      TTC_Typedef (TerminalTypeConstant.java:525)
      + 68 more covering TypeConstant subclasses, UnionTypeConstant,
        IntersectionTypeConstant, DifferenceTypeConstant, AnnotatedTypeConstant,
        ParameterizedTypeConstant, RelationalTypeConstant, compiler AST nodes.

    record(pool, site, resolved, success): hot-path write. Emits 8-field Object[]
    fact into Kotlin JournalSeries+ReduxMutableSeries via reflection:
      [factId, nano, poolId, siteOrd, clsName, format, success, isReverted]
    Strings interned into Kotlin StringPool lazily. Returns factId or -1.

    revert/revertSite/revertPool: Redux compensating-fact operations for rollback
    by factId, by (pool, site) pair, or by entire pool.

    metaAsRowVec(): projects Redux cold state into "keys|cells" string for Confix.

    getKotlinInstance(): one-time reflection bootstrap caching all Method handles.
    KOTLIN_CLASS = "borg.trikeshed.cursor.TypedefResolutionSeries". If absent,
    kotlinAvailable = false and all calls short-circuit immediately.



    PART 3: TABULAR TYPE PRODUCTION — THE RULE TABLES
    ==================================================

    3A. FORMAT-KEYED DISPATCH (the master subtype table)
    TypeConstant.calculateRelationToContribution()  TypeConstant.java:5515-5641

    switch (constIdRight.getFormat()):
      Module / Package / Class / NativeClass
        -> ClassStructure.calculateRelation(pool, typeLeft, typeRight)
        Rule: nominal inheritance

      Property
        if same-format AND same name -> IS_A
        else -> constraint type of id.calculateRelation(typeLeft)
        Rule: formal generic type param same-name identity

      TypeParameter
        if same-format AND (same name OR same identity) -> IS_A
        else -> constraint delegation
        Rule: formal type parameter identity

      FormalTypeChild (T.X)
        if same-format AND same name -> IS_A
        else -> constraint delegation

      ThisClass / ParentClass / ChildClass (pseudo-constants, auto-narrowing)
        if congruent (isCongruentWith):
          strip auto-narrowing from both sides
          try typeRight.calculateRelation(typeLeft) -> if not INCOMPATIBLE return it
          else try typeLeft.calculateRelation(typeRight)
        else -> ClassStructure.calculateRelation(pool, typeLeft, typeRight)
        Rule: auto-narrowing bidirectional compatibility

      Typedef
        -> getReferredToType().calculateRelation(typeLeft)
        Rule: alias expansion and retry

      IsConst / IsEnum / IsModule / IsPackage / IsClass (keyword constraints)
        -> ((KeywordConstant) constIdRight).getBaseType().calculateRelationToContribution(typeLeft)
        Rule: keyword constraint delegation

      UnresolvedName
        -> INCOMPATIBLE
        Rule: fail-safe for unresolved symbols

      default: IllegalStateException

    3B. INCOMPATIBILITY SEALING TABLE
    TypeConstant.isIncompatibleComboImpl()  TypeConstant.java:846-884

    switch (clzThis.getFormat()):
      ENUMVALUE / PACKAGE / MODULE -> always true (sealed singleton)
      ENUM -> false only if one enum child isA the other type; else true
      CLASS / CONST / SERVICE -> true if other is also CLASS/CONST/SERVICE/ENUM/ENUMVALUE/PACKAGE/MODULE

    3C. CONTRIBUTION LIST FORMAT DISPATCH
    TypeConstant.createContributionList()  TypeConstant.java:2449-2560

    switch (struct.getFormat()):
      PACKAGE / MODULE / ENUMVALUE / ENUM / CLASS / CONST / SERVICE:
        compute typeRebase
        check if first contribution is Composition.Extends
        if not: ENUMVALUE is an error (VE_EXTENDS_EXPECTED); others: implicit Object extends
        if yes: validate isExplicitClassIdentity, check cyclical extends, check isExtendsLegal(format)
        Rule: concrete class contribution ordering

      ANNOTATION / MIXIN:
        check first contribution is Composition.Into -> typeInto
        then check Composition.Extends -> typeExtends (must match same format)
        if no Into clause: implicit into Object
        Rule: mixin contribution ordering

    3D. TYPEINFO PRODUCTION PIPELINE (the output schema)
    TypeConstant.buildTypeInfoImpl()  TypeConstant.java:1978-2127

    Input: this TypeConstant
    Pipeline:
      1. Access dispatch:
         STRUCT -> buildStructInfo()
         PUBLIC -> build PRIVATE version, then limitAccess(PUBLIC)
         PRIVATE -> main pipeline below
      2. Annotation dispatch: AnnotatedTypeConstant -> buildPrivateInfo()
      3. PropertyClassTypeConstant dispatch -> its own buildTypeInfo()
      4. listContribs = struct.getContributionsAsList()
      5. atypeContrib = resolveContributionTypes(listContribs)
      6. atypeSpecial = createContributionList(...) -> [typeInto, typeExtends, typeRebase]
      7. mapTypeParams = collectTypeParameters(...)
      8. createCallChains() -> listmapClassChain + listmapDefaultChain
      9. collectMemberInfo() -> mapProps/mapMethods/mapVirtProps/mapVirtMethods/mapChildren
     10. TypeInfo(this, ..., Progress.Complete or Progress.Incomplete)

    Output schema (TypeInfo constructor fields):
      mapTypeParams: Map<Object, ParamInfo>      -- String key = type param; NestedIdentity key = Ref-exploded
      aannoClass: Annotation[]                   -- class-mixing annotations
      aannoMixin: Annotation[]                   -- regular mixin annotations
      typeExtends: TypeConstant                  -- nominal superclass link
      typeRebases: TypeConstant                  -- rebase layer link
      typeInto: TypeConstant                     -- mixin target
      listProcess: List<Contribution>            -- ordered contribution list
      listmapClassChain: ListMap<IdentityConstant, Origin>    -- virtual dispatch order
      listmapDefaultChain: ListMap<IdentityConstant, Origin>  -- interface default order
      mapProps: Map<PropertyConstant, PropertyInfo>           -- non-virtual props
      mapVirtProps: Map<Object, PropertyInfo>                 -- virtual props by nested identity
      mapMethods: Map<MethodConstant, MethodInfo>             -- non-virtual methods
      mapVirtMethods: Map<Object, MethodInfo>                 -- virtual methods
      mapChildren: ListMap<String, ChildInfo>    -- child types by name
      setDepends: Set<TypeConstant>              -- null = complete; non-null = deferred
      fCacheById / fCacheByNid: ConcurrentHashMap -- pre-built from mapMethods for thread-safe lookup

    3E. FORMAL TYPE DISPATCH (TerminalTypeConstant override)
    TerminalTypeConstant.buildTypeInfo()  TerminalTypeConstant.java:1451-1492

    switch (constant.getFormat()):
      Module / Package / Class / NativeClass -> super.buildTypeInfo() (base pipeline)
      IsClass / IsConst / IsEnum / IsModule / IsPackage
        -> ((KeywordConstant) constant).getBaseType().ensureTypeInfoInternal(errs)
      Property / TypeParameter / FormalTypeChild / DynamicFormal
        resolve constraint type
        if containsAutoNarrowing: resolveAutoNarrowingBase() first
        if infoConstraint incomplete: return null (deferred)
        else: return new TypeInfo(this, infoConstraint, cInvalidations)
        -- formal type's TypeInfo wraps constraint's TypeInfo, keyed by the formal

    3F. METHOD OVERLOAD RESOLUTION
    TypeInfo.findMethods()  TypeInfo.java:1975-2025

    Lazy secondary index keyed by "name;paramCount" + kind key.
    Filter criteria:
      idMethod.isTopLevel() -- only top-level methods
      kind.matches(method) -- kind filter (Any/Function/Constructor/etc.)
      !info.isCapped() -- skip fully overridden methods
      arity window: cRequired = cAllParams - cTypeParams - cDefaults
                    match if cRequired <= cParams <= cAllParams



    PART 4: RUNTIME TYPE COMPOSITION PIPELINE
    ==========================================

    ObjectHandle.m_clazz (ObjectHandle.java:48)
      The single TypeComposition pointer on every live object.
      Swapped (not copied) by cloneAs() to change the vtable/mask view.

    ObjectHandle.cloneAs(TypeComposition clazz) (line 63)
      super.clone() then handle.m_clazz = clazz.
      Zero field copy. All object field storage is unchanged.
      GenericHandle overrides to also copy the field map.
      This is the mechanism for type-state DSL transitions and access masking.

    ClassComposition fields (ClassComposition.java:860-959)
      f_typeInception  TypeConstant  -- always Access.PRIVATE
      f_typeStructure  TypeConstant  -- always Access.STRUCT
      f_typeRevealed   TypeConstant  -- the public mask/view
      All three share the same 6 ConcurrentHashMap caches via f_clzInception.
      Derivative compositions (maskAs, revealAs) share all caches -- only f_typeRevealed differs.

    ClassComposition.maskAs(TypeConstant type) (line 164)
      if f_typeRevealed.isA(type): computeIfAbsent derivative composition
      else: null

    ClassComposition.revealAs(TypeConstant type) (line 173)
      STRUCT check first, then PRIVATE check, then null.

    Frame.resolveType(TypeConstant type) (Frame.java:1471)
      1. if containsFormalType(true): resolveGenerics(pool, getGenericsResolver(...))
      2. if f_hThis != null AND containsAutoNarrowing(true):
         resolveAutoNarrowing(pool, false, f_hThis.getType(), null)
      This is the central hub for all runtime type lookups. Called at assignment
      sites, NEW ops, CAST ops, register type computation.

    Frame.checkType(ObjectHandle hValueFrom, VarInfo infoTo) (Frame.java:861)
      Quick path: typeFrom.getPosition() == infoTo.m_nTypeId -> skip
      calculateRelation(typeTo):
        IS_A -> ok
        IS_A_WEAK -> if typeOfType: ok; else fall through
        default -> revealOrigin(), retry:
          IS_A -> ok
          IS_A_WEAK -> log WARNING: wrapping required (proxy needed)
          default -> check for CanonicalizedTypeComposition, unresolved generics,
                     PropertyClassTypeConstant, DelegateHandle exemptions;
                     else log WARNING: suspicious assignment



    PART 5: INTEGRATION TEST — TypedefCascadeDagReificationTest
    ============================================================

    File: javatools/src/test/java/org/xvm/runtime/TypedefCascadeDagReificationTest.java

    5 tests:

    liveDag_allCallsiteOrdinalsFiring (line 70)
      Activates publisher, compiles minimal module with stdlib, drains publisher.
      Builds TypedefCascadeTable, appends one rule per TypedefCallsite ordinal.
      Asserts table.ruleCount() == sites.length (73).

    liveDag_cascadeTableFromLiveFacts (line 138)
      Compiles 5-hop typedef chain A->B->C->D->E->Int.
      Builds TypedefCascadeTable with opcodes in [0x10, 0xA8].
      Calls reduce(). Asserts ruleCount > 0 OR depth histogram total > 0.

    noCycles_resolveTypedefsTerminates (line 222)
      Compiles "typedef ChainA as ChainA;" (self-ref).
      Asserts publisher size is consistent (parser catches cycle before resolveTypedefs fires).

    simdReady_allKindBucketsNonEmpty (line 283)
      Compiles 5 typedefs covering Int/String/union/List/tuple.
      Appends 5 rows to TypedefCascadeTable with KIND_RETURN/KIND_TYPE/KIND_FIELD/KIND_ALLOC.
      After reduce() asserts exact histogram:
        kh[KIND_RETURN]=2  kh[KIND_TYPE]=1  kh[KIND_FIELD]=1  kh[KIND_ALLOC]=1
        dh[0]=2  dh[1]=3
        sh[SCOPE_CLASS]=4  sh[SCOPE_METHOD]=1
        successCount()==5  rowCount()==5

    adjacentRuleSoA_coversAll73CallsiteOrdinals (line 398)
      Pure unit test, no compilation.
      Builds AdjacentRule SoA from all 73 TypedefCallsite ordinals.
      Asserts ruleCount==sites.length, matchRule(0x65)>=0, matchRule(0x66)>=0, matchRule(0x00)==-1.
