package com.seaofnodes.simple

import com.seaofnodes.simple.codegen.CodeGen
import com.seaofnodes.simple.node.*
import com.seaofnodes.simple.node.BoolNode.EQ
import com.seaofnodes.simple.node.BoolNode.LT
import com.seaofnodes.simple.node.ScopeNode.Kind.*
import com.seaofnodes.simple.print.GraphVisualizer
import com.seaofnodes.simple.type.*
import com.seaofnodes.simple.util.Ary
import java.lang.Double
import java.lang.Long
import java.util.*
import kotlin.Any
import kotlin.Boolean
import kotlin.Byte
import kotlin.ByteArray
import kotlin.Char
import kotlin.Int
import kotlin.RuntimeException
import kotlin.String
import kotlin.TODO
import kotlin.also
import kotlin.arrayOfNulls
import kotlin.assert
import kotlin.checkNotNull
import kotlin.code
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.indices
import kotlin.collections.minus
import kotlin.collections.minusAssign
import kotlin.invoke
import kotlin.sequences.minus
import kotlin.text.String
import kotlin.text.indexOf
import kotlin.text.intern
import kotlin.text.toLong
import kotlin.toString
import kotlin.toUShort
import kotlin.unaryMinus

/**
 * The Parser converts a Simple source program to the Sea of Nodes intermediate
 * representation directly in one pass.  There is no intermediate Abstract
 * Syntax Tree structure.
 *
 *
 * This is a simple recursive descent parser. All lexical analysis is done here as well.
 */
class Parser(code: CodeGen, arg: TypeInteger?) {
    // Compile driver
    val _code: CodeGen = code

    // The Lexer.  Thin wrapper over a byte[] buffer with a cursor.
    private var _lexer: Lexer? = null

    /**
     * Current ScopeNode - ScopeNodes change as we parse code, but at any point of time
     * there is one current ScopeNode. The reason the current ScopeNode can change is to do with how
     * we handle branching. See [.parseIf].
     *
     *
     * Each ScopeNode contains a stack of lexical scopes, each scope is a symbol table that binds
     * variable names to Nodes.  The top of this stack represents current scope.
     *
     *
     * We keep a list of all ScopeNodes so that we can show them in graphs.
     * @see .parseIf
     * @see ._xScopes
     */
    var _scope: ScopeNode?

    /**
     * We clone ScopeNodes when control flows branch; it is useful to have
     * a list of all active ScopeNodes for purposes of visualization of the SoN graph
     */
    val _xScopes: Stack<ScopeNode?> = Stack<ScopeNode?>()

    var _continueScope: ScopeNode?
    var _breakScope: ScopeNode? // Merge all the while-breaks here
    var _fun: FunNode? = null // Current function being parsed

    // Mapping from a type name to the constructor for a Type.
    val INITS: HashMap<String?, StructNode>


    override fun toString(): String {
        return _lexer.toString()
    }

    // Debugging utility to find a Node by index
    fun f(nid: Int): Node? {
        return _code.f(nid)
    }

    private fun ctrl(): Node? {
        return _scope!!.ctrl()
    }

    private fun <N : Node?> ctrl(n: N?): N? {
        return _scope!!.ctrl<N?>(n)
    }

    fun parse() {
        _scope!!.define(ScopeNode.Companion.CTRL, Type.Companion.CONTROL, false, null, _lexer)
        _scope!!.define(ScopeNode.Companion.MEM0, TypeMem.Companion.BOT, false, null, _lexer)
        _scope!!.define(ScopeNode.Companion.ARG0, TypeInteger.Companion.BOT, false, null, _lexer)

        ctrl<XCtrlNode?>(XCTRL)
        _scope!!.mem(MemMergeNode(false))

        // Parse the sys import
        _lexer = Lexer(sys.SYS)
        while (!_lexer.isEOF) {
            parseStatement()
            _lexer.skipWhiteSpace()
        }

        // Reset lexer for program text
        _lexer = Lexer(_code._src)
        _xScopes.push(_scope)

        // Parse whole program, as-if function header "{ int arg -> body }"
        parseFunctionBody(_code._main, loc(), "arg")

        // Kill an empty default main.  Keep only if it was explicitly defined
        // (programmer asked for a "main") or it has stuff (i.e. beginner
        // default main).
        val main = _code.link(_code._main)
        val stop = _code._stop
        // Gather an explicit main
        var xmain: FunNode? = null
        for (n in stop._inputs) if (n is ReturnNode && "main" == n.`fun`()._name) if (xmain != null) throw TODO()
        else xmain = n.`fun`()

        if (main.ret().expr()._type === Type.Companion.TOP && main.uctrl() == null) {
            // Kill an empty default main; so it does not attempt to put a
            // "main" in any final ELF file
            main.setDef<XCtrlNode?>(1, XCTRL) // Delete default start input
            stop.delDef(stop._inputs.find(main.ret()))
            if (xmain != null) _code.setMain(xmain)
        } else {
            // We have a non-empty default main.
            if (xmain != null)  // Found an explicit "main" AND we have a default "main"
                throw error("Cannot define both an explicit main and a default main")
            main._name = "main"
        }

        if (!_lexer.isEOF) throw _errorSyntax("unexpected")

        // Clean up and reset
        _xScopes.pop()
        _scope!!.kill()
        for (init in INITS.values()) init.unkeep<Node?>().kill()
        INITS.clear()
        stop.peephole()
    }

    /**
     * Parses a function body, assuming the header is parsed.
     */
    private fun parseFunctionBody(sig: TypeFunPtr, loc: Lexer?, vararg ids: String?): ReturnNode {
        // If this is a method, record & restore the existing struct fields,
        // which are also the struct initializers.  The method might update
        // mutable fields, so when the function parse is done reset back to
        // pre-function-parse values.
        val preParse = arrayOfNulls<Node>(_scope!!.nIns())
        for (i in 0..<_scope!!.nIns()) preParse[i] =
            if (_scope!!.`in`(i) == null) null else _scope!!.`in`(i).keep<Node?>()

        // Stack parser state on the local Java stack, and unstack it later
        val oldfun = _fun!!
        val breakScope = _breakScope
        _breakScope = null
        val continueScope = _continueScope
        _continueScope = null

        _fun = peep(FunNode(loc(), sig, _nestedType, null, _code._start)) as FunNode
        val `fun` = _fun!!
        // Once the function header is available, install in linker table -
        // allowing recursive functions.  Linker matches on declared args and
        // exact fidx, and ignores the return (because the fidx will only match
        // the exact single function).
        _code.link(`fun`)

        val rpc = ParmNode("\$rpc", 0, TypeRPC.Companion.BOT, `fun`, Companion.con(TypeRPC.Companion.BOT)).peephole()

        // Build a multi-exit return point for all function returns
        val r = RegionNode(null, null, null).init<RegionNode>()
        assert(r.inProgress())
        val rmem = PhiNode(ScopeNode.Companion.MEM0, TypeMem.Companion.BOT, r, null).init<PhiNode>()
        val rrez = PhiNode(ScopeNode.Companion.ARG0, Type.Companion.BOTTOM, r, null).init<PhiNode>()
        var ret = ReturnNode(r, rmem, rrez, rpc, `fun`).init<ReturnNode>()
        `fun`.setRet(ret)
        assert(ret.inProgress())
        _code._stop.addDef(ret)


        // Pre-call the function from Start, with worse-case arguments.  This
        // represents all the future, yet-to-be-parsed functions calls and
        // external calls.
        _scope!!.push(Func())
        ctrl<FunNode?>(`fun`) // Scope control from function
        // Private mem alias tracking per function
        val mem = MemMergeNode(true)
        mem.addDef(null) // Alias#0
        mem.addDef(
            ParmNode(
                ScopeNode.Companion.MEM0,
                1,
                TypeMem.Companion.BOT,
                `fun`,
                Companion.con(TypeMem.Companion.BOT)
            ).peephole()
        ) // All aliases
        _scope!!.mem(mem)
        // All args, "as-if" called externally
        for (i in ids.indices) {
            val t = sig.arg(i)
            _scope!!.define(ids[i], t, false, ParmNode(ids[i], i + 2, t, `fun`, con(t)).peephole(), loc)
        }

        // Parse the body
        var last: Node? = ZERO
        while (!peek('}') && !_lexer.isEOF) last = parseStatement()

        // Last expression is the return except for the top-level main
        if (ctrl()!!._type === Type.Companion.CONTROL) if (`fun`.sig() !== _code._main) `fun`.addReturn(
            ctrl(),
            _scope!!.mem().merge(),
            last
        )
        else {
            `fun`.setDef<XCtrlNode?>(1, XCTRL) // Kill default main
            _code.addAll(`fun`._outputs)
        }

        // Pop off the inProgress node on the multi-exit Region merge
        assert(r.inProgress())
        r._inputs.pop()
        rmem._inputs.pop()
        rrez._inputs.pop()
        r._loc = loc() // Final position
        assert(!r.inProgress())

        // Force peeps, which have been avoided due to inProgress
        ret.setDef<Node?>(1, rmem.peephole())
        ret.setDef<Node?>(2, rrez.peephole())
        ret.setDef<Node?>(0, r.peephole())
        ret = ret.peephole() as ReturnNode

        // Function scope ends
        _scope!!.pop()
        _fun = oldfun
        _breakScope = breakScope
        _continueScope = continueScope

        // Reset all fields to pre-parse days
        for (i in preParse.indices) _scope!!.setDef<Node?>(
            i,
            if (preParse[i] == null) null else preParse[i]!!.unkeep<Node?>()
        )

        return ret
    }

    /**
     * Parses a block
     *
     * <pre>
     * '{' statements '}'
    </pre> *
     * Does not parse the opening or closing '{}'
     * @return a [Node] or `null`
     */
    private fun parseBlock(kind: ScopeNode.Kind): Node? {
        // Enter a new scope
        _scope!!.push(kind)
        var last: Node? = ZERO
        while (!peek('}') && !_lexer.isEOF) last = parseStatement()
        // Exit scope
        last!!.keep<Node?>()
        _scope!!.pop()
        return last.unkeep<Node?>()
    }

    /**
     * Parses a statement
     *
     * <pre>
     * returnStatement | declStatement | blockStatement | ifStatement | expressionStatement
    </pre> *
     * @return a [Node] or `null`
     */
    private fun parseStatement(): Node? {
        if (matchx("return")) return parseReturn()
        else if (matchx("if")) return parseIf()
        else if (matchx("while")) return parseWhile()
        else if (matchx("for")) return parseFor()
        else if (matchx("break")) return parseBreak()
        else if (matchx("continue")) return parseContinue()
        else if (matchx("struct")) return parseStruct()
        else if (matchx("#showGraph")) return require<Node?>(showGraph(), ";")
        else if (matchx(";")) return null // Empty statement
        else if (peek('{') && !this.isTypeFun) {
            match("{")
            return require<Node?>(parseBlock(ScopeNode.Kind.Block()), "}")
        } else return parseDeclarationStatement()
    }

    /**
     * Parses a while statement
     *
     * <pre>
     * while ( expression ) statement
    </pre> *
     * @return a [Node], never `null`
     */
    private fun parseWhile(): Node {
        require("(")
        return parseLooping(false)
    }


    /**
     * Parses a for statement
     *
     * <pre>
     * for( var x=init; test; incr ) body
    </pre> *
     * @return a [Node], never `null`
     */
    private fun parseFor(): Node {
        // {   var x=init,y=init,...;
        //     while( test ) {
        //         body;
        //         next;
        //     }
        // }
        require("(")
        _scope!!.push(ScopeNode.Kind.Block()) // Scope for the index variables
        if (!match(";"))  // Can be empty init "for(;test;next) body"
            parseDeclarationStatement() // Non-empty init

        val rez = parseLooping(true)
        _scope!!.pop() // Exit index variable scope
        return rez
    }

    // Shared by `for` and `while`
    private fun parseLooping(doFor: Boolean): Node {
        val savedContinueScope = _continueScope
        val savedBreakScope = _breakScope

        // Loop region has two control inputs, the first is the entry
        // point, and second is back edge that is set after loop is parsed
        // (see end_loop() call below).  Note that the absence of back edge is
        // used as an indicator to switch off peepholes of the region and
        // associated phis; see {@code inProgress()}.
        ctrl<Node?>(LoopNode(loc(), ctrl()).peephole()) // Note we set back edge to null here

        // At loop head, we clone the current Scope (this includes all
        // names in every nesting level within the Scope).
        // We create phis eagerly for all the names we find, see dup().

        // Save the current scope as the loop head
        val head = _scope!!.keep<ScopeNode>()
        // Clone the head Scope to create a new Scope for the body.
        // Create phis eagerly as part of cloning
        _xScopes.push(_scope!!.dup(true).also { _scope = it }) // The true argument triggers creating phis

        // Parse predicate
        val pred = if (peek(';')) con(1) else parseAsgn()
        require(if (doFor) ";" else ")")

        // IfNode takes current control and predicate
        val ifNode = IfNode(ctrl(), pred.keep<Node?>()).peephole()
        // Setup projection nodes
        val ifT = CProjNode(ifNode.keep<Node?>(), 0, "True").peephole().keep<Node>()
        val ifF = CProjNode(ifNode.unkeep<Node?>(), 1, "False").peephole()

        // for( ;;next ) body
        var nextPos = -1
        var nextEnd = -1
        if (doFor) {
            // Skip the next expression and parse it later
            nextPos = pos()
            skipAsgn()
            nextEnd = pos()
            require(")")
        }

        // Clone the body Scope to create the break/exit Scope which accounts for any
        // side effects in the predicate.  The break/exit Scope will be the final
        // scope after the loop, and its control input is the False branch of
        // the loop predicate.  Note that body Scope is still our current scope.
        ctrl<Node?>(ifF)
        _xScopes.push(_scope!!.dup().also { _breakScope = it })
        _breakScope!!.addGuards(ifF, pred, true) // Up-cast predicate

        // No continues yet
        _continueScope = null

        // Parse the true side, which corresponds to loop body
        // Our current scope is the body Scope
        ctrl<Node?>(ifT.unkeep<Node?>()) // set ctrl token to ifTrue projection
        _scope!!.addGuards(ifT, pred.unkeep<Node?>(), false) // Up-cast predicate
        parseStatement()!!.isKill() // Parse loop body
        _scope!!.removeGuards(ifT)

        // Merge the loop bottom into other continue statements
        if (_continueScope != null) {
            _continueScope = jumpTo(_continueScope)
            _scope!!.kill()
            _scope = _continueScope
        }

        // Now append the next code onto the body code
        if (doFor) {
            val old = pos(nextPos)
            if (!peek(')')) parseAsgn()
            if (pos() != nextEnd) throw errorSyntax("Unexpected code after expression")
            pos(old)
        }

        // The true branch loops back, so whatever is current _scope.ctrl gets
        // added to head loop as input.  endLoop() updates the head scope, and
        // goes through all the phis that were created earlier.  For each phi,
        // it sets the second input to the corresponding input from the back
        // edge.  If the phi is redundant, it is replaced by its sole input.
        val exit = _breakScope
        head.endLoop(_scope, exit)
        head.unkeep<Node?>().kill()

        _xScopes.pop() // Cleanup
        _xScopes.pop() // Cleanup

        _continueScope = savedContinueScope
        _breakScope = savedBreakScope

        // At exit the false control is the current control, and
        // the scope is the exit scope after the exit test.
        // During sys parsing, there is no xscope here.
        if (!_xScopes.isEmpty()) {
            _xScopes.pop()
            _xScopes.push(exit)
        }
        _scope = exit
        return ZERO
    }

    private fun jumpTo(toScope: ScopeNode?): ScopeNode {
        val cur = _scope!!.dup()
        ctrl<XCtrlNode?>(XCTRL) // Kill current scope
        // Prune nested lexical scopes that have depth > than the loop head
        // We use _breakScope as a proxy for the loop head scope to obtain the depth
        while (cur.depth() > _breakScope!!.depth()) cur.pop()
        // If this is a continue then first time the target is null
        // So we just use the pruned current scope as the base for the
        // "continue"
        if (toScope == null) return cur
        // toScope is either the break scope, or a scope that was created here
        assert(toScope.depth() <= _breakScope!!.depth())
        toScope.ctrl<Node?>(toScope.mergeScopes(cur, loc()).peephole())
        return toScope
    }

    private fun checkLoopActive() {
        if (_breakScope == null) throw error("No active loop for a break or continue")
    }

    private fun parseContinue(): Node {
        checkLoopActive()
        _continueScope = require<ScopeNode?>(jumpTo(_continueScope), ";")
        return ZERO
    }

    private fun parseBreak(): Node {
        checkLoopActive()
        // At the time of the break, and loop-exit conditions are only valid if
        // they are ALSO valid at the break.  It is the intersection of
        // conditions here, not the union.
        _breakScope!!.removeGuards(_breakScope!!.ctrl())
        _breakScope = require<ScopeNode?>(jumpTo(_breakScope), ";")
        _breakScope!!.addGuards(_breakScope!!.ctrl(), null, false)
        return ZERO
    }

    // Look for an unbalanced `)`, skipping balanced
    private fun skipAsgn() {
        var paren = 0
        while (true)  // Next X char handles skipping complex comments
            when (_lexer!!.nextXChar()) {
                Character.MAX_VALUE -> throw TODO()
                ')' -> if (--paren < 0) {
                    posT(pos() - 1)
                    return  // Leave the `)` behind
                }

                '(' -> paren++
                else -> {}
            }
    }


    /**
     * Parses a statement
     *
     * <pre>
     * if ( expression ) statement [else statement]
    </pre> *
     * @return a [Node], never `null`
     */
    private fun parseIf(): Node {
        // Parse predicate
        require("(")
        val pred = require<Node>(parseAsgn(), ")")!!
        return parseTrinary(pred, "else")
    }

    // Parse a conditional expression, merging results.
    private fun parseTrinary(pred: Node, fside: String): Node {
        // IfNode takes current control and predicate
        pred.keep<Node?>()
        val ifNode = IfNode(ctrl(), pred).peephole()
        // Setup projection nodes
        val ifT = CProjNode(ifNode.keep<Node?>(), 0, "True").peephole().keep<Node>()
        val ifF = CProjNode(ifNode.unkeep<Node?>(), 1, "False").peephole().keep<Node>()
        // In if true branch, the ifT proj node becomes the ctrl
        // But first clone the scope and set it as current
        var fScope = _scope!!.dup() // Duplicate current scope
        _xScopes.push(fScope) // For graph visualization we need all scopes

        // Parse the true side
        ctrl<Node?>(ifT.unkeep<Node?>()) // set ctrl token to ifTrue projection
        _scope!!.addGuards(ifT, pred, false) // Up-cast predicate
        // Parse true-side flavor
        var lhs = when (fside) {
            "else" -> parseStatement()
            ":" -> parseAsgn()
            "&&" -> parseLogical()
            "||" -> pred
            else -> throw TODO()
        }
        lhs!!.keep<Node?>()
        _scope!!.removeGuards(ifT)

        // See if a one-sided def was made: "if(pred) int x = 1;" and throw.
        // See if any forward-refs were made, and copy them to the other side:
        // "pred ? n*fact(n-1) : 1"
        fScope!!.balanceIf(_scope)

        val tScope = _scope

        // Parse the false side
        _scope = fScope // Restore scope, then parse else block if any
        ctrl<Node?>(ifF.unkeep<Node?>()) // Ctrl token is now set to ifFalse projection
        // Up-cast predicate, even if not else clause, because predicate can
        // remain true if the true clause exits: `if( !ptr ) return 0; return ptr.fld;`
        _scope!!.addGuards(ifF, pred, true)
        // Parse false-side flavor
        var doRHS = false // RHS is optional for if/else and trinary
        var rhs: Node? = when (fside) {
            "else" -> if (match(fside).also { doRHS = it }) parseStatement() else con(lhs._type.makeZero())
            ":" -> if (match(fside).also { doRHS = it }) parseAsgn() else con(lhs._type.makeZero())
            "&&" -> rhs = pred
            "||" -> rhs = parseLogical()
            else -> throw TODO()
        }
        rhs!!.keep<Node?>()
        _scope!!.removeGuards(ifF)
        if (doRHS) fScope = _scope
        pred.unkeep<Node?>()

        // Check the trinary widening int/flt
        if (fside != "else") {
            rhs = widenInt(rhs.unkeep<Node?>(), lhs._type)!!.keep<Node?>()
            lhs = widenInt(lhs.unkeep<Node?>(), rhs._type)!!.keep<Node?>()
        }

        _scope = tScope
        _xScopes.pop() // Discard pushed from graph display
        // See if a one-sided def was made: "if(pred) int x = 1;" and throw.
        // See if any forward-refs were made, and copy them to the other side:
        // "pred ? n*fact(n-1) : 1"
        tScope!!.balanceIf(fScope)

        // Merge results
        val r = ctrl<RegionNode>(tScope.mergeScopes(fScope, loc()))!!
        val ret =
            peep(PhiNode("", lhs!!._type.meet(rhs!!._type).glb(false), r, lhs.unkeep<Node?>(), rhs.unkeep<Node?>()))
        // Immediately fail e.g. `arg ? 7 : ptr`
        val err: ParseException?
        if (fside != "else" && (ret.err().also { err = it }) != null) throw err
        r.peephole()
        return ret
    }

    /**
     * Parses a return statement; "return" already parsed.
     * The $ctrl edge is killed.
     *
     * <pre>
     * 'return' expr ;
    </pre> *
     * @return an expression [Node], never `null`
     */
    private fun parseReturn(): Node? {
        val expr = require<Node?>(parseAsgn(), ";")
        // Need default memory, since it can be lazy, need to force
        // a non-lazy Phi
        _fun!!.addReturn(ctrl(), _scope!!.mem().merge(), expr)
        ctrl<XCtrlNode?>(XCTRL) // Kill control
        return expr
    }

    /**
     * Dumps out the node graph
     * @return `null`
     */
    fun showGraph(): Node? {
        System.out.println(GraphVisualizer().generateDotOutput(_code._stop, _scope, _xScopes))
        return null
    }

    /** Parse: [name '='] expr
     */
    private fun parseAsgn(): Node {
        val old = pos()
        val name = _lexer!!.matchId()
        val old_pre_equals = pos()
        // Just a plain expression, no assignment.
        // Distinguish `var==expr` from `var=expr`
        if (name == null || KEYWORDS.contains(name) || !matchOpx('=', '=')) {
            pos(old)
            return parseExpression()
        }

        // Find variable to update
        val def = _scope!!.lookup(name)
        if (def == null) throw error("Undefined name '" + name + "'")

        // If a method var, allow nested blocks and methods, but not fcns or other structs.
        // Else safety check for accessing fields out of function scope.
        if (instance(def)) {
            pos(old_pre_equals) // setup for `self.name = expr`
            val self = _scope!!.`in`(_scope!!.lookup("self")) // Insert self reference
            return parsePostfixName(self, name)
        }

        // TOP fields are for late-initialized fields; these have never
        // been written to, and this must be the final write.  Other writes
        // outside the constructor need to check the final bit.
        if (_scope!!.`in`(def._idx)._type !== Type.Companion.TOP && def._final &&  // Inside an allocation, final assign is OK, outside nope.
            // The alloc() call added the allocation scope
            !(_scope!!.inAllocation() && def._idx >= _scope!!._kinds.last(-1)._lexSize)
        ) throw error("Cannot reassign final '" + name + "'")

        // Parse assignment expression
        val expr = parseAsgn()

        // Lift expression, based on type
        val lift = liftExpr(expr.keep<Node?>(), def.type(), def._final, true)
        // Update
        _scope!!.update(name, lift)
        // Return un-lifted expr
        return expr.unkeep<Node>()
    }

    // Make finals deep; widen ints to floats; narrow wide int types.
    // Early error if types do not match variable.
    private fun liftExpr(expr: Node, t: Type, xfinal: Boolean, isLoad: Boolean): Node {
        var expr = expr
        var t = t
        if (expr._type is TypeMemPtr && TYPES.get(_type._obj._name)!!
                .isFRef()
        ) throw error("Must define forward ref " + _type._obj._name)
        // Final is deep on ptrs
        if (xfinal && t is TypeMemPtr) {
            t = t.makeRO()
            expr = peep(ReadOnlyNode(expr))
        }
        // Auto-widen array to i64 (cast ptr to raw int bits)
        if (t === TypeInteger.Companion.BOT && expr._type is TypeMemPtr && _type._obj.isAry()) expr =
            peep(AddNode(peep(CastNode(t, ctrl(), expr)), off(_type._obj, "[]")))
        // Auto-widen int to float
        expr = widenInt(expr, t)!!
        // Auto-narrow wide ints to narrow ints.  For loads, emit code to force
        // the loaded value to match the declared sign/zero bits.  For stores,
        // just force the type, acting "as if" the store silently truncates.
        var et = expr._type
        if (isLoad) {
            expr = zsMask(expr, t)
            et = expr._type
        } else if (et is TypeInteger && t is TypeInteger) et = t

        // Type is sane
        if (et !== Type.Companion.BOTTOM && !et.shallowISA(t)) expr =
            peep(CastNode(if (t.isConstant()) t else t.widen(), null, expr))
        return expr
    }

    private fun widenInt(expr: Node, t: Type?): Node? {
        return if ((expr._type is TypeInteger || expr._type === Type.Companion.NIL) && t is TypeFloat)
            peep(ToFloatNode(expr))
        else
            expr
    }

    /**
     * Parse declaration or expression statement
     * declStmt = type var['=' exprAsgn][, var['=' exprAsgn]]* ';' | exprAsgn ';'
     *
     *
     * exprAsgn = var '=' exprAsgn | expr
     */
    private fun parseDeclarationStatement(): Node? {
        val old = pos()
        var t = type()
        if (peek('.'))  // Ambiguity static vars: "type.var", parse as expression
        {
            pos(old)
            t = null
        }
        if (t == null) return require<Node?>(parseAsgn(), ";")

        // now parse var['=' asgnexpr] in a loop
        var n = parseDeclaration(t)
        while (match(",")) n = parseDeclaration(t)
        return require<Node?>(n, ";")
    }

    /** Parse final: [!]var['=' asgn]
     */
    private fun parseDeclaration(t: Type): Node {
        var t = t
        checkNotNull(t)
        // Has var/val instead of a user-declared type
        val inferType = t === Type.Companion.TOP || t === Type.Companion.BOTTOM
        val hasBang = match("!")
        val loc = loc()
        val name = requireId()
        // Optional initializing expression follows
        var xfinal = false
        var fld_final = false // Field is final, but not deeply final
        val expr: Node
        if (match("=")) {
            expr = if (this.isExternDecl)
                externDecl(name, t)
            else
                parseAsgn()
            // TOP means val and val is always final
            xfinal = (t === Type.Companion.TOP) ||
                    expr is ExternNode ||  // BOTTOM is var and var is always not-final
                    (t !== Type.Companion.BOTTOM &&  // no Bang AND
                            !hasBang &&  // Locals are not-final by default
                            !_scope!!.inFunction() &&  // not-null (expecting null to be set to not-null)
                            expr._type !== Type.Companion.NIL &&  // Pointers are final by default; int/flt are not-final by default.
                            (t is TypeNil))

            // var/val, then type comes from expression
            if (inferType) {
                if (expr._type === Type.Companion.NIL) throw error("a not-null/non-zero expression")
                t = expr._type
                if (!xfinal) t = t.glb(false) // Widen if not final
            }
            // expr is a constant function
            if (t is TypeFunPtr && expr._type is TypeFunPtr && _type.isConstant()) {
                if (expr is ExternNode) t = expr._type // Upgrade declared type to exact function
                else _code.link(_type).setName(name) // Assign debug name to Simple function
            }
        } else {
            // Need an expression to infer the type.
            // Also, if not-null then need an initializing expression.
            // Allowed in a class def, because type/init will happen in the constructor.
            if ((inferType || (t is TypeNil && !t.nullable())) && !_scope!!.inConstructor()) throw errorSyntax("=expression")
            // Initial value for uninitialized struct fields.
            expr = when (t) {
                -> if (tn.nullable()) NIL else Companion.con(Type.Companion.TOP)
                -> ZERO
                -> Companion.con(TypeFloat.Companion.FZERO)
                -> {
                    assert(tt === Type.Companion.BOTTOM)
                    Companion.con(tt)
                }
            }
            // Nullable fields are set in the constructor, but remain shallow final.
            // e.g. final pointer to a r/w array
            if (t is TypeNil && !t.nullable() && !hasBang) fld_final = true
        }

        // Lift expression, based on type
        var lift = liftExpr(expr, t, xfinal, true)
        // Rebuild the deep RO 't', not returned from liftExpr
        if (xfinal && t is TypeMemPtr) t = t.makeRO()

        // Lift type to the declaration.  This will report as an error later if
        // we cannot lift the type.
        if (!lift._type.isa(t)) lift = peep(CastNode(t.widen(), null, lift))
        // Define a new name
        if (!_scope!!.define(name, t, xfinal || fld_final, lift, loc)) throw error(
            "Redefining name '" + name + "'",
            loc
        )
        return lift
    }


    /**
     * Parse a struct declaration, and return the following statement.
     * Structs cannot be redefined, but can be nested.
     *
     * @return zero
     */
    private var _nestedType: String? = null
    private fun parseStruct(): Node {
        // "struct" already parsed, so expect the struct name next.
        val old = _nestedType
        var typeName = requireId()
        if (old != null) typeName = old + "." + typeName
        _nestedType = typeName
        // New declared or forward-ref type
        var ts: TypeStruct = if (TYPES.get(typeName) is TypeMemPtr) tmp._obj else TypeStruct.Companion.open(typeName)
        TYPES.put(typeName, TypeMemPtr.Companion.make(ts))

        // A Block scope parse, and inspect the scope afterward for fields.
        _scope!!.push(Define(TypeMemPtr.Companion.make(ts)))
        require("{")
        val lexlen = _scope!!._kinds.last()._lexSize
        var nvar = lexlen
        while (!peek('}') && !_lexer.isEOF) {
            parseStatement()!!.isKill()
            while (nvar < _scope!!._vars._len) {
                val v = _scope!!.`var`(nvar++)
                if (!v.isFRef()) {
                    ts = ts.add(
                        Field.Companion.make(
                            v._name,
                            v.type(),
                            _code.getALIAS(),
                            v._final,
                            v._final && _scope!!.`in`(v._idx)._type !== Type.Companion.TOP
                        )
                    )
                    TYPES.put(typeName, TypeMemPtr.Companion.make(ts))
                }
            }
        }

        // Count struct declarations
        val varlen = _scope!!._vars._len
        ts = ts.close()
        TYPES.put(typeName, TypeMemPtr.Companion.make(ts))

        // Generate an initializing struct
        val s = StructNode(ts)
        for (i in lexlen..<varlen) if (!_scope!!.`var`(i).isFRef())  // Promote to outer scope, not defined here
            s.addDef(_scope!!.`in`(i)) // Keep the program code to generate instance


        // Save instance default constructor
        s.setType(s.compute()) // Do not call peephole, in case the entire struct is a big plain constant
        INITS.put(typeName, s.keep<StructNode?>())

        // Having finalized the struct, upgrade all the 'self' pointers
        for (n in s._inputs) if (n._type is TypeFunPtr && _code.link(_type) is FunNode) _code.addAll(`fun`._outputs)


        // Done with struct/block scope
        _nestedType = old
        require("}")
        require(";")
        _scope!!.pop()
        return ZERO
    }


    // Parse and return a type or null.  Valid types always are followed by an
    // 'id' which the caller must parse.  This lets us distinguish forward ref
    // types (which ARE valid here) from local vars in an (optional) forward
    // ref type position.
    // t = int|i8|i16|i32|i64|u8|u16|u32|u64|byte|bool | flt|f32|f64 | val | var | struct[?]
    private fun type(): Type? {
        val old1 = pos()
        // Only type with a leading `{` is a function pointer...
        if (peek('{')) return typeFunPtr()

        // Otherwise you get a type name
        var tname = _lexer!!.matchId()
        if (tname == null) return null

        // Convert the type name to a type.
        var t0: Type? = TYPES.get(tname)
        // No new types as keywords
        if (t0 == null && KEYWORDS.contains(tname)) return posT(old1)
        if (t0 === Type.Companion.BOTTOM || t0 === Type.Companion.TOP) return t0 // var/val type inference


        // Check for subtype.
        while (true) {
            val old2 = pos()
            if (!match(".")) break
            val sname = _lexer!!.matchId()
            if (sname == null) {
                pos(old2)
                break
            }
            val tsname = tname + "." + sname
            t0 = TYPES.get(tsname)
            if (t0 == null) {
                pos(old2)
                t0 = TYPES.get(tname)
                break
            }
            tname = tsname
        }

        // Still no type found?  Assume forward reference
        val t1 =
            if (t0 == null) TypeMemPtr.Companion.make(TypeStruct.Companion.open(tname)) else t0 // Null: assume a forward ref type
        // Nest arrays and '?' as needed
        var t2 = t1
        while (true) {
            if (match("?")) {
                if (t2 !is TypeMemPtr) throw error("Type " + t0 + " cannot be null")
                if (t2.nullable()) throw error("Type " + t2 + " already allows null")
                t2 = t2.makeNullable()
            } else if (match("[~]")) {
                t2 = typeAry(t2, true)
            } else if (match("[]")) {
                t2 = typeAry(t2, false)
            } else break
        }

        // Check no forward ref
        if (t0 != null) return t2
        // Check valid forward ref, after parsing all the type extra bits.
        // Cannot check earlier, because cannot find required 'id' until after "[]?" syntax
        val old2 = pos()
        match("!")
        val id = _lexer!!.matchId()
        if (!(peek(',') || peek(';') || match("->")) || id == null) return posT(old1) // Reset lexer to reparse

        pos(old2) // Reset lexer to reparse
        // Yes a forward ref, so declare it
        TYPES.put(tname, t1)
        // Return the (array, final) type
        return t2
    }

    // Make an array type of t
    private fun typeAry(t: Type, efinal: Boolean): TypeMemPtr {
        if (t is TypeMemPtr && t.notNull()) throw error("Arrays of reference types must always be nullable")
        val tname = "[]" + t.str()
        var ta = TYPES.get(tname) as TypeMemPtr?
        if (ta == null)  // Remember final version
            TYPES.put(
                tname,
                TypeMemPtr.Companion.make(
                    TypeStruct.Companion.makeAry(
                        tname,
                        TypeInteger.Companion.U32,
                        _code.getALIAS(),
                        t,
                        _code.getALIAS(),
                        true
                    )
                ).also { ta = it })
        val elem = ta!!._obj.field("[]")
        if (elem._final == efinal) return ta
        return TypeMemPtr.Companion.make(ta._obj.replace(elem.makeFrom(efinal)))
    }

    // A function type is `{ type... -> type }` or `{ type }`.
    private fun typeFunPtr(): Type? {
        val old = pos() // Record lexer position
        match("{") // Skip already-peeked '{'
        val t0 = type() // Either return or first arg
        if (t0 == null) return posT(old) // Not a function

        if (match("}"))  // No-arg function { -> type }
            return TypeFunPtr.Companion.make(match("?"), false, TypeFunPtr.Companion.TEMPTY, t0)
        val ts = Ary<Type?>(Type::class.java)
        ts.push(t0) // First argument
        while (true) {
            if (match("->")) { // End of arguments, parse return
                val ret = type()
                if (ret == null || !match("}")) return posT(old) // Not a function

                return TypeFunPtr.Companion.make(match("?"), false, ts.asAry(), ret)
            }
            val t1 = type()
            if (t1 == null) return posT(old) // Not a function

            ts.push(t1)
        }
    }

    private val isTypeFun: Boolean
        // True if a TypeFunPtr, without advancing parser
        get() {
            val old = pos()
            if (typeFunPtr() == null) return false
            pos(old)
            return true
        }

    /**
     * Parse an expression of the form:
     *
     * <pre>
     * expr : bitwise [? expr [: expr]]
    </pre> *
     * @return an expression [Node], never `null`
     */
    private fun parseExpression(): Node {
        val expr = parseLogical()
        return if (match("?")) parseTrinary(expr, ":") else expr
    }

    /**
     * Parse an bitwise expression
     *
     * <pre>
     * bitwise : compareExpr (('&' | '|' | '^') compareExpr)*
    </pre> *
     * @return a bitwise expression [Node], never `null`
     */
    private fun parseLogical(): Node {
        var lhs = parseBitwise()
        while (true) {
            if (false) ; else if (match("&&")) lhs = parseTrinary(lhs, "&&")
            else if (match("||")) lhs = parseTrinary(lhs, "||")
            else break
        }
        return lhs
    }

    private fun parseBitwise(): Node {
        var lhs = parseEquality()
        while (true) {
            if (false) ; else if (matchOp('&')) lhs = AndNode(loc(), lhs, null)
            else if (matchOp('|')) lhs = OrNode(loc(), lhs, null)
            else if (match("^")) lhs = XorNode(loc(), lhs, null)
            else break
            lhs.setDef<Node?>(2, parseEquality())
            lhs = peep(lhs)
        }
        return lhs
    }


    /**
     * Parse an eq/ne expression
     */
    private fun parseEquality(): Node {
        var lhs = parseComparison()
        while (true) {
            var eq = false
            if (match("==")) eq = true
            else if (!match("!=")) break
            lhs.keep<Node?>()
            val rhs = parseComparison()
            lhs = peep(EQ(lhs.unkeep<Node?>(), rhs).widen())
            if (!eq) lhs = peep(NotNode(lhs))
        }
        return lhs
    }

    /**
     * Parse an expression of the form:
     *
     * <pre>
     * expr : shiftExpr < shiftExpr <= shiftExpr...
     * expr : shiftExpr > shiftExpr >= shiftExpr...
    </pre> *
     * @return an comparator expression [Node], never `null`
     */
    private fun parseComparison(): Node {
        var lhs = parseShift()
        var dir = 0

        while (true) {
            var dir0: Int
            if (false) ; else if (match("<=")) dir0 = -1
            else if (match(">=")) dir0 = -2
            else if (match("<")) dir0 = 1
            else if (match(">")) dir0 = 2
            else break
            lhs.keep<Node?>()
            // Check and record direction
            if (dir == 0) {
                dir = Math.abs(dir0)
                val rhs = parseShift()
                lhs = makeCompBool(dir0, lhs.unkeep<Node?>(), rhs) // Convert to a bool
            } else if (dir == Math.abs(dir0)) {
                // e0 < lhs < ???
                val ifNode = IfNode(ctrl(), lhs).peephole()
                val ifT = CProjNode(ifNode.keep<Node?>(), 0, "True").peephole()
                val ifF = CProjNode(ifNode.unkeep<Node?>(), 1, "False").peephole()
                // False side does nothing
                val fScope = _scope!!.dup()
                fScope.ctrl<Node?>(ifF)
                // True side executes next part of range
                ctrl<Node?>(ifT)
                _scope!!.addGuards(ifT, lhs, false)
                val rhs = parseShift()
                _scope!!.removeGuards(ifT) // TODO: I think should remain true as long as possible
                val next = makeCompBool(dir0, lhs, rhs).keep<Node>()
                // Merge result
                val r = ctrl<RegionNode>(_scope!!.mergeScopes(fScope, loc()))!!
                assert(next._type.meet(lhs._type).isa(TypeInteger.Companion.BOOL))
                lhs = peep(PhiNode("", TypeInteger.Companion.BOOL, r, next.unkeep<Node?>(), lhs.unkeep<Node?>()))
                r.peephole()
            } else {
                throw error("Mixing relational directions in a chained relational test")
            }
        }
        return lhs
    }

    private fun makeCompBool(dir0: Int, lhs: Node, rhs: Node): Node {
        // Convert to a bool
        var lhs = lhs
        var rhs = rhs
        if (Math.abs(dir0) == 2)  // Swap direction
        {
            val tmp = lhs
            lhs = rhs
            rhs = tmp
        }
        lhs = peep(if (dir0 < 0) BoolNode.LE(lhs, rhs) else LT(lhs, rhs))
        return peep(lhs.widen())
    }


    /**
     * Parse an additive expression
     *
     * <pre>
     * shiftExpr : additiveExpr (('<<' | '>>' | '>>>') additiveExpr)*
    </pre> *
     * @return a shift expression [Node], never `null`
     */
    private fun parseShift(): Node {
        var lhs = parseAddition()
        while (true) {
            if (false) ; else if (match("<<")) lhs = ShlNode(loc(), lhs, null)
            else if (match(">>>")) lhs = ShrNode(loc(), lhs, null)
            else if (match(">>")) lhs = SarNode(loc(), lhs, null)
            else break
            lhs.setDef<Node?>(2, parseAddition())
            val err: ParseException?
            if ((lhs.err().also { err = it }) != null) throw err
            lhs = peep(lhs.widen())
        }
        return lhs
    }

    /**
     * Parse an additive expression
     *
     * <pre>
     * additiveExpr : multiplicativeExpr (('+' | '-') multiplicativeExpr)*
    </pre> *
     * @return an add expression [Node], never `null`
     */
    private fun parseAddition(): Node {
        var lhs = parseMultiplication()
        while (true) {
            if (false) ; else if (match("+")) lhs = AddNode(lhs, null)
            else if (match("-")) lhs = SubNode(lhs, null)
            else break
            lhs.setDef<Node?>(2, parseMultiplication())
            lhs = peep(lhs.widen())
        }
        return lhs
    }

    /**
     * Parse an multiplicativeExpr expression
     *
     * <pre>
     * multiplicativeExpr : unaryExpr (('*' | '/') unaryExpr)*
    </pre> *
     * @return a multiply expression [Node], never `null`
     */
    private fun parseMultiplication(): Node {
        var lhs = parseUnary()
        while (true) {
            if (false) ; else if (match("*")) lhs = MulNode(lhs, null)
            else if (match("/")) lhs = DivNode(lhs, null)
            else break
            lhs.setDef<Node?>(2, parseUnary())
            lhs = peep(lhs.widen())
        }
        return lhs
    }

    /**
     * Parse a unary minus expression.
     *
     * <pre>
     * unaryExpr : ('-') unaryExpr | '!') unaryExpr | postfixExpr | primaryExpr | '--' Id | '++' Id
    </pre> *
     * @return a unary expression [Node], never `null`
     */
    private fun parseUnary(): Node {
        // Pre-dec/pre-inc
        val old = pos()
        if (match("--") || match("++")) {
            val delta = if (_lexer!!.peek(-1) == '+'.code.toByte()) 1 else -1 // Pre vs post
            val name = _lexer!!.matchId()
            if (name != null) {
                val n = _scope!!.lookup(name)
                if (n != null && n.type() !is TypeMemPtr) {
                    if (n._final) throw error("Cannot reassign final '" + n._name + "'")
                    val expr = if (n.type() is TypeFloat)
                        peep(AddFNode(_scope!!.`in`(n), Companion.con(TypeFloat.Companion.constant(delta.toDouble()))))
                    else
                        zsMask(peep(AddNode(_scope!!.`in`(n), con(delta.toLong()))), n.type())
                    _scope!!.update(n, expr)
                    return expr
                }
            }
            // Reset, try again
            pos(old)
        }
        if (match("-")) return peep(MinusNode(parseUnary()).widen())
        if (match("!")) return peep(NotNode(parseUnary()))
        // Else be a primary expression
        return parsePrimary()
    }

    /**
     * Parse a primary expression:
     *
     * <pre>
     * primaryExpr : integerLiteral | "string" | 'char' | true | false | null |
     * new Type | '(' expression ')' | Id['++','--'] |
     * [static.]* Id
    </pre> *
     * @return a primary [Node], never `null`
     */
    private fun parsePrimary(): Node {
        if (_lexer!!.isNumber(_lexer.peek())) return parseLiteral()
        if (_lexer!!.peek('"')) return newString(parseString()!!)
        if (matchx("true")) return con(1)
        if (matchx("false")) return ZERO
        if (matchx("null")) return NIL
        if (match("'")) return parseChar()
        if (match("(")) return parsePostfix(require<Node?>(parseAsgn(), ")")!!)
        if (matchx("new")) return parsePostfix(alloc()!!)
        if (match("{")) return parsePostfix(require<Node?>(func(), "}")!!)

        // Parse static variable lookup: `type.fld` in the type namespace
        // CNC: not a full type parse, just a typename parse
        val pos = pos()
        val t = type()
        if (t != null) {
            if (peek('.')) return parsePostfix(con(t))
            //pos(pos); // TODO: not a `type.fld`
            //return null;
            throw TODO()
        }

        // Expect an identifier now

        // - if null, make fref, normal var handling
        // - if final constant, normal var handling
        // - if method var, scan skipping blocks & methods, must hit correct struct no fcns
        // - if normal var, scan skipping blocks, allowing last fcn
        // - NO fcns nested in methods, NO methods nested in fcns
        val id = _lexer!!.matchId()
        if (id == null || KEYWORDS.contains(id)) throw errorSyntax("an identifier or expression")
        var `var` = _scope!!.lookup(id)

        if (`var` == null) {
            // If missing, assume a forward reference
            _scope!!.define(id, FRefNode.Companion.FREF_TYPE, true, XCTRL, loc())
            `var` = _scope!!.lookup(id)
        }

        // If a method var, allow nested blocks and methods, but not fcns or other structs.
        // Else safety check for accessing fields out of function scope.
        if (instance(`var`)) {
            val self = _scope!!.`in`(_scope!!.lookup("self")) // Insert self reference
            return parsePostfixName(self, id)
        }

        // Load local value
        val rvalue = _scope!!.`in`(`var`)
        if (rvalue._type === Type.Companion.BOTTOM) if (rvalue is FRefNode) return parsePostfix(rvalue)
        else throw error("Cannot read uninitialized field '" + id + "'")

        // Check for assign-update, x += e0;
        val ch = _lexer!!.matchOperAssign()
        if (ch.code == 0)  // Normal primary, check for postfix updates
            return parsePostfix(rvalue)
        // Assign-update direct into Scope
        val op = opAssign(ch, rvalue, `var`.type())
        _scope!!.update(`var`, op)
        return if (postfix(ch)) rvalue.unkeep<Node>() else op
    }

    // true if instance variable lookup (var is in any number of nested blocks,
    // then an instance function then inside a struct declaration).
    private fun instance(`var`: Var): Boolean {
        // If a method var, allow nested blocks and methods, but not fcns or other structs
        val kind = _scope!!.kind(`var`)
        if (kind is Define) {
            var i = _scope!!.depth() - 1
            while (i >= 0 && `var`._idx < _scope!!._kinds.at(i)._lexSize) {
                val xkind = _scope!!._kinds.at(i)
                if (xkind is ScopeNode.Kind.Block) {
                    i--
                    continue
                }
                if (xkind is Func) {
                    if (_scope!!._kinds.at(i - 1) === kind) return true // Instance variable: lookup in a method in a decl
                }
                throw TODO()
                i--
            }
        } else {
            // If a normal var, allow any nested blocks, and a last fcn -
            // but not functions further out (which involves capture).
            val def = _scope!!.`in`(`var`._idx)
            var i = _scope!!.depth() - 1
            while (i >= 0 && `var`._idx < _scope!!._kinds.at(i)._lexSize) {
                if (_scope!!._kinds.at(i) is Func && (def !is FRefNode) && !(`var`._final && def._type.isConstant())) throw error(
                    "Variable '" + `var`._name + "' is out of function scope and must be a final constant"
                )
                i--
            }
        }
        return false
    }

    // Check for assign-update, "x += e0".
    // Returns "x + e0"; the caller assigns value.
    // if ch is postfix, then lhs is kept and caller will unkeep and return it.
    private fun opAssign(ch: Char, lhs: Node, t: Type): Node {
        // RHS of the update.
        lhs.keep<Node?>() // Alive across parseAsgn
        val rhs =
            if (ch.code.toByte().toInt() == 1) con(1) else  // var++
                if (ch.code.toByte().toInt() == -1) con(-1) else  // var--
                    parseAsgn() // var op= rhs
        if (!postfix(ch)) lhs.unkeep<Node?>() // Allow to die in next peep

        // 4 cases:
        // int + int ==>> narrow int
        // int + flt ==>> error, caller must fail assigning flt into int
        // flt + int ==>> use float op, wrap toFloat()
        // flt + flt ==>> use float op
        val op: Node = when (ch) {
            1, -1.toChar(), '+' -> AddNode(lhs, rhs)
            '-' -> SubNode(lhs, rhs)
            '*' -> MulNode(lhs, rhs)
            '/' -> DivNode(lhs, rhs)
            else -> throw TODO()
        }
        // Convert to float ops, or narrow int types; error if not declared type.
        // Also, if postfix LHS is still keep()
        return liftExpr(peep(op.widen()), t, false, true)
    }


    /**
     * Parse an allocation
     */
    private fun alloc(): Node? {
        val t = type()
        if (t == null) throw error("Expected a type")
        // Parse ary[ length_expr ]
        if (match("[")) {
            if (!t.makeZero().isa(t)) throw error("Cannot allocate a non-nullable, since arrays always zero/null fill")
            val len = parseAsgn()
            if (len._type !is TypeInteger) throw error("Cannot allocate an array with length " + len._type)
            require("]")
            val tmp = typeAry(t, false)
            return newArray(tmp._obj, len)
        }

        if (t !is TypeMemPtr) throw error("Cannot allocate a " + t.str())

        // Parse new struct { default_initialization }
        val s: StructNode = INITS.get(t._obj._name)!!
        if (s == null) throw error("Unknown struct type '" + t._obj._name + "'")

        val fs = s._ts._fields
        // if the object is fully initialized, we can skip a block here.
        // Check for constructor block:
        val hasConstructor = match("{")
        var init = s._inputs
        var idx = 0
        if (hasConstructor) {
            idx = _scope!!.nIns()
            // Push a scope, and pre-assign all struct fields.
            _scope!!.push(ScopeNode.Kind.Block())
            val loc = loc()
            for (i in fs.indices)  // An initial TOP means the field needs to be initialized.  We
            // store a BOT initially; any partial init will fall to BOT
            // (merge BOT and the partial) and be obviously only a partial
            // init.  To be initialized the field needs a full clobber.
                _scope!!.define(
                    fs[i]._fname, fs[i]._t, fs[i]._final, if (s.`in`(i)._type === Type.Companion.TOP) Companion.con(
                        Type.Companion.BOTTOM
                    ) else s.`in`(i), loc
                )
            // Parse the constructor body
            require<Node?>(parseBlock(Alloc(t)), "}")
            init = _scope!!._inputs
        }
        // Check that all fields are initialized
        for (i in idx..<init.size()) if (init.at(i)._type === Type.Companion.TOP || init.at(i)._type === Type.Companion.BOTTOM) throw error(
            "'" + t._obj._name + "' is not fully initialized, field '" + fs[i - idx]._fname + "' needs to be set in a constructor"
        )
        val ptr = newStruct(t._obj, off(t._obj, " len"), idx, init)
        if (hasConstructor) _scope!!.pop()
        return ptr
    }


    /**
     * Return a NewNode initialized memory.
     * @param obj is the declared type, with GLB fields
     * @param init is a collection of initialized fields
     */
    private fun newStruct(obj: TypeStruct, size: Node?, idx: Int, init: Ary<Node>): Node? {
        val fs = obj._fields
        if (fs == null) throw error("Unknown struct type '" + obj._name + "'")
        val len: Int = fs.length
        val ns = arrayOfNulls<Node>(2 + len)
        ns[0] = ctrl() // Control in slot 0
        // Total allocated length in bytes
        ns[1] = size
        // Memory aliases for every field
        for (i in 0..<len) if (!fs[i]._one) ns[2 + i] = memAlias(fs[i]._alias)
        val nnn = NewNode(TypeMemPtr.Companion.make(obj), *ns).peephole().keep<Node>()
        for (i in 0..<len) if (!fs[i]._one) memAlias(
            fs[i]._alias,
            ProjNode(nnn, i + 2, memName(fs[i]._alias)).peephole()
        )
        val ptr = ProjNode(nnn.unkeep<Node?>(), 1, obj._name).peephole().keep<Node>()

        // Initial nonzero values for every field
        for (i in 0..<len) {
            val `val` = init.get(i + idx)
            if (!fs[i]._one && `val`._type !== `val`._type.makeZero()) {
                val mem = memAlias(fs[i]._alias)
                val st = StoreNode(
                    loc(),
                    fs[i]._fname,
                    fs[i]._alias,
                    fs[i]._t,
                    mem,
                    ptr,
                    off(obj, fs[i]._fname),
                    `val`,
                    true
                ).peephole()
                memAlias(fs[i]._alias, st)
            }
        }

        return ptr.unkeep<Node?>()
    }

    init {
        _scope = ScopeNode()
        _breakScope = null
        _continueScope = _breakScope
        ZERO = Companion.con(TypeInteger.Companion.ZERO).keep<ConstantNode>()
        NIL = Companion.con(Type.Companion.NIL).keep<ConstantNode>()
        XCTRL = XCtrlNode().peephole().keep<XCtrlNode?>()
        TYPES = defaultTypes()
        INITS = HashMap<String?, StructNode>()
    }

    private fun newArray(ary: TypeStruct, len: Node): Node? {
        val base: ConFldOffNode? = off(ary, "[]")
        val scale = ary.aryScale()
        val size = peep(AddNode(base, peep(ShlNode(null, len.keep<Node?>(), con(scale.toLong())))))
        ALTMP.clear()
        ALTMP.add(len.unkeep<Node?>())
        ALTMP.add(con(ary._fields[1]._t.makeZero()))
        return newStruct(ary, size, 0, ALTMP)
    }

    private fun newString(s: String): Node {
        val tmp = typeAry(TypeInteger.Companion.U8, true)
        val lenAlias = tmp._obj.field("#")._alias
        val elemAlias = tmp._obj.field("[]")._alias
        val con: TypeConAryB = TypeConAryB.Companion.make(s)
        val elem = con.elem()
        // Make a TMP, not-null (byte)2, singleton (true) (requires text
        // strings are hash-interned), with a TypeStruct having a constant
        // array body.
        val str: TypeMemPtr = TypeMemPtr.Companion.make(
            2.toByte(),
            TypeStruct.Companion.makeAry(
                "[]u8",
                TypeInteger.Companion.constant(s.length().toLong()),
                lenAlias,
                con,
                elemAlias,
                true
            ),
            true
        )
        assert(str.isConstant())
        return con(str)
    }

    // We set up memory aliases by inserting special vars in the scope these
    // variables are prefixed by $ so they cannot be referenced in Simple code.
    // Using vars has the benefit that all the existing machinery of scoping
    // and phis work as expected
    private fun memAlias(alias: Int): Node? {
        return _scope!!.mem(alias)
    }

    private fun memAlias(alias: Int, st: Node?) {
        _scope!!.mem(alias, st)
    }

    /**
     * Parse postfix expression; this can be a field expression, an array
     * lookup or a postfix operator like '#'
     *
     * <pre>
     * expr ('.' FIELD)* [ = expr ]       // Field reference
     * expr '#'                           // Postfix unary read operator
     * expr ['++' | '--' ]                // Postfix unary write operator
     * expr ('[' expr ']')* = [ = expr ]  // Array reference
     * expr '(' [args,]* ')'              // Function call
    </pre> *
     */
    private fun parsePostfix(expr: Node): Node {
        val name: String?
        if (match(".")) name = requireId()
        else if (match("#")) name = "#"
        else if (match("[")) name = "[]"
        else if (match("(")) return parsePostfix(require<Node?>(functionCall(expr, null), ")")!!)
        else return expr // No postfix


        return parsePostfixName(expr, name)
    }

    /**
     * Parse postfix expression; this can be a field expression, an array
     * lookup or a postfix operator like '#'
     *
     * <pre>
     * expr ['++' | '--' ]                 // Postfix unary write operator
     * expr [ [op]= expr ]                 // Field reference
     * expr ('[' expr ']')* [ [op]= expr ] // Array reference
    </pre> *
     */
    private fun parsePostfixName(expr: Node, name: String): Node {
        if (expr._type === Type.Companion.NIL) throw error("Accessing unknown field '" + name + "' from 'null'")

        // Sanity check expr for being a reference
        if (expr._type !is TypeMemPtr) throw error("Expected " + (if (name === "#" || name === "[]") "array" else "reference") + " but found " + expr._type.str())

        // Find the field from the Type.  Lookup in the base object field names.
        val ptr2 = TYPES.get(_type._obj._name) as TypeMemPtr?
        _type = _type.join(ptr2) as TypeMemPtr? // Upgrade to latest TYPES
        val base: TypeStruct = _type._obj
        val fidx = base.find(name)
        if (fidx == -1) throw error("Accessing unknown field '" + name + "' from '" + _type.str() + "'")

        // Get field type and layout offset from base type and field index fidx
        val f = base._fields[fidx] // Field from field index
        var tf = f._t
        if (tf is TypeMemPtr && tf.isFRef()) tf = tf.makeFrom(((TYPES.get(tf._obj._name)) as TypeMemPtr)._obj)
        if (base.isAry() && tf is TypeConAry<*>) tf = tf.elem()

        // Field offset; fixed for structs, computed for arrays
        val off = (if (name == "[]" // If field is an array body
        // Array index math
        )
            peep(
                AddNode(
                    com.seaofnodes.simple.Parser.Companion.off(base, "[]"),
                    peep(
                        ShlNode(
                            null,
                            require<com.seaofnodes.simple.node.Node?>(parseAsgn(), "]"),
                            com.seaofnodes.simple.Parser.Companion.con(base.aryScale().toLong())
                        )
                    )
                )
            ) // Struct field offsets are hardwired
        else
            com.seaofnodes.simple.Parser.Companion.off(base, name))!!.keep<Node>()

        // Disambiguate "obj.fld==x" boolean test from "obj.fld=x" field assignment
        if (matchOpx('=', '=')) {
            val `val` = parseAsgn().keep<Node>()
            val lift = liftExpr(`val`, tf, f._final, false)

            val st: Node =
                StoreNode(loc(), name, f._alias, tf, memAlias(f._alias), expr, off.unkeep<Node?>(), lift, false)
            // Arrays include control, as a proxy for a safety range check.
            // Structs don't need this; they only need a NPE check which is
            // done via the type system.
            if (base.isAry()) st.setDef<Node?>(0, ctrl())
            memAlias(f._alias, st.peephole())
            return `val`.unkeep<Node>() // "obj.a = expr" returns the expression while updating memory
        }

        // Keep expr for possible store update
        expr.keep<Node?>()
        // Load field
        var load = if (f._one && INITS.containsKey(base._name))
            INITS.get(base._name)!!.`in`(base.find(name))
        else
            LoadNode(loc(), name, f._alias, tf, memAlias(f._alias), expr, off)
        // Arrays include control, as a proxy for a safety range check
        // Structs don't need this; they only need a NPE check which is
        // done via the type system.
        if (base.isAry() && name != "#") load.setDef<Node?>(0, ctrl())
        load = peep(load)

        // Check for assign-update, "ptr.fld += expr" or "ary[idx]++"
        val ch = _lexer!!.matchOperAssign()
        if (ch.code != 0) {
            val op = opAssign(ch, load, tf)
            val st: Node = StoreNode(
                loc(),
                name,
                f._alias,
                tf,
                memAlias(f._alias),
                expr.unkeep<Node?>(),
                off.unkeep<Node?>(),
                op,
                false
            )
            // Arrays include control, as a proxy for a safety range check.
            // Structs don't need this; they only need a NPE check which is
            // done via the type system.
            if (base.isAry()) st.setDef<Node?>(0, ctrl())
            memAlias(f._alias, peep(st))
            load = if (postfix(ch)) load.unkeep<Node>() else op
            // And use the original loaded value as the result
            return load
            //throw Utils.TODO(); // NO POSTFIX AFTER STORE, JUST RETURN
        } else {
            off.unkill()
            // Method call: Loaded a function from a named field, AND the base
            // field type is final.
            if (load._type is TypeFunPtr && name !== "[]" && (load !is ExternNode) &&
                (TYPES.get(base._name) as TypeMemPtr)._obj.field(name)._final &&  // And calling a function
                match("(")
            ) {
                return parsePostfix(require<Node?>(functionCall(load, expr), ")")!!)
            }

            // Drop expr, keeping load alive
            load.keep<Node?>()
            expr.unkill()
            load.unkeep<Node?>()
        }

        return parsePostfix(load)
    }


    // zero/sign extend.  "i" is limited to either classic unsigned (min==0) or
    // classic signed (min=minus-power-of-2); max=power-of-2-minus-1.
    private fun zsMask(`val`: Node, t: Type?): Node {
        if (!(`val`._type is TypeInteger && t is TypeInteger && !_type.isa(t))) {
            if (!(`val`._type is TypeFloat && t is TypeFloat && !_type.isa(t))) return `val`
            // Float rounding
            return peep(RoundF32Node(`val`))
        }
        if (t._min == 0L)  // Unsigned
            return peep(AndNode(null, `val`, con(t._max)))
        // Signed extension
        val shift = Long.numberOfLeadingZeros(t._max) - 1
        val shf: Node = con(shift.toLong())
        if (shf._type === TypeInteger.Companion.ZERO) return `val`
        return peep(SarNode(null, peep(ShlNode(null, `val`, shf.keep<Node?>())), shf.unkeep<Node?>()))
    }

    /**
     * Parse a function body; the caller will parse the surrounding "{}"
     *
     * <pre>
     * { [type arg,]* -> expr }
     * { expr } // The no-argument function
    </pre> *
     */
    private fun func(): Node {
        val ts = Ary<Type?>(Type::class.java)
        val ids = Ary<String?>(String::class.java)

        // Defined in constructor?  Add `self` argument.  "static" calls still
        // add a `self` they just ignore it.
        if (_scope!!._kinds.last() is Define) {
            // Upgrade defined struct to latest fields
            val tmp = TYPES.get(define._tmp._obj._name) as TypeMemPtr?
            ts.push(tmp)
            ids.push("self")
        }

        // Parse other arguments
        _lexer.skipWhiteSpace()
        val loc = loc() // First argument location
        while (true) {
            val t = type() // Arg type
            if (t == null) break
            val id = requireId()
            ts.push(t) // Push type/arg pairs
            ids.push(id)
            match(",")
        }
        require("->")
        // Make a concrete function type, with a fidx
        val tfp = _code.makeFun(TypeFunPtr.Companion.make(false, false, ts.asAry(), Type.Companion.BOTTOM))
        val ret = parseFunctionBody(tfp, loc, *ids.asAry())
        return con(ret._fun.sig())
    }

    /**
     * Parse function call arguments; caller will parse the surrounding "()"
     * <pre>
     * ( arg* )
    </pre> *
     */
    private fun functionCall(fcn: Node, self: Node?): Node {
        if (fcn._type === Type.Companion.NIL) throw error("Calling a null function pointer")
        if (fcn !is FRefNode && !fcn._type.isa(TypeFunPtr.Companion.BOT)) throw error(
            "Expected a function but got " + fcn._type.glb(
                false
            ).str()
        )
        fcn.keep<Node?>() // Keep while parsing args

        val args = Ary<Node>(Node::class.java)
        args.push(null) // Space for ctrl,mem
        args.push(null)
        if (self != null)  // Method call has a self
            args.push(self)
        while (!peek(')')) {
            val arg = parseAsgn()
            if (arg == null) break
            args.push(arg.keep<Node?>())
            if (!match(",")) break
        }
        // Control & memory after parsing args
        args.set(0, ctrl()!!.keep<Node?>())
        args.set(1, _scope!!.mem().merge().keep<Node?>())
        args.push(fcn) // Function pointer
        // Unkeep them all
        for (arg in args) arg.unkeep<Node?>()
        // Dead into the call?  Skip all the node gen
        if (ctrl()!!._type === Type.Companion.XCONTROL) {
            for (arg in args) if (arg.isUnused()) arg.kill()
            return Companion.con(Type.Companion.TOP)
        }

        // Into the call
        val call = CallNode(loc(), *args.asAry()).peephole() as CallNode
        // Post-call setup
        val cend = CallEndNode(call).peephole() as CallEndNode
        call.peephole() // Rerun peeps after CallEnd, allows early inlining
        // Control from CallEnd
        ctrl<Node?>(CProjNode(cend, 0, ScopeNode.Companion.CTRL).peephole())
        // Memory from CallEnd
        val mem = MemMergeNode(true)
        mem.addDef(null) // Alias#0
        mem.addDef(ProjNode(cend, 1, ScopeNode.Companion.MEM0).peephole())
        _scope!!.mem(mem)
        // Call result
        return ProjNode(cend, 2, "#2").peephole()
    }

    private val isExternDecl: Boolean
        // Just after parsing "type foo = " can parse `"C"`
        get() {
            val old = pos()
            val s = parseString()
            if ("C" == s) return true
            pos(old)
            return false
        }

    // External linked constant
    private fun externDecl(ex: String?, t: Type?): Node {
        var t = t
        if (t is TypeFunPtr)  // Generic TFP from type parse
            t = _code.makeFun(t) // Get a FIDX, becomes a constant

        return ExternNode(t, ex).peephole()
    }

    /**
     * Parse integer literal
     *
     * <pre>
     * integerLiteral: [1-9][0-9]* | [0]
     * floatLiteral: [digits].[digits]?[e [digits]]?
    </pre> *
     */
    private fun parseLiteral(): ConstantNode {
        return con(_lexer.parseNumber())
    }

    fun peep(n: Node): Node {
        // Peephole, then improve with lexically scoped guards
        return _scope!!.upcastGuard(n.peephole())
    }

    // Parse a string or null
    private fun parseString(): String? {
        if (!peek('"')) return null
        _lexer.inc()
        val start = pos()
        while (!_lexer.isEOF && _lexer.nextChar() != '"');
        if (_lexer.isEOF) throw error("Unclosed string")
        return String(_lexer._input, start, pos() - start - 1)
    }

    // Already parsed "'"
    private fun parseChar(): Node {
        return require<ConstantNode>(
            com.seaofnodes.simple.Parser.Companion.con(TypeInteger.Companion.constant(_lexer.nextChar().code.toLong())),
            "'"
        )!!
    }

    /**/////////////////////////////// */ // Utilities for lexical analysis
    // Return true and skip if "syntax" is next in the stream.
    private fun match(syntax: String): Boolean {
        return _lexer!!.match(syntax)
    }

    // Match must be "exact", not be followed by more id letters
    private fun matchx(syntax: String): Boolean {
        return _lexer!!.matchx(syntax)
    }

    private fun matchOp(c0: Char): Boolean {
        return _lexer!!.matchOp(c0)
    }

    private fun matchOpx(c0: Char, c1: Char): Boolean {
        return _lexer!!.matchOpx(c0, c1)
    }

    // Return true and do NOT skip if 'ch' is next
    private fun peek(ch: Char): Boolean {
        return _lexer!!.peek(ch)
    }

    private fun peekIsId(): Boolean {
        return _lexer!!.peekIsId()
    }

    fun pos(): Int {
        return _lexer._position
    }

    private fun pos(pos: Int): Int {
        val old = _lexer._position
        _lexer._position = pos
        return old
    }

    private fun posT(pos: Int): Type? {
        _lexer._position = pos
        return null
    }

    // Source code location
    fun loc(): Lexer {
        return Parser.Lexer(_lexer)
    }


    // Require and return an identifier
    private fun requireId(): String {
        val id = _lexer!!.matchId()
        if (id != null && !KEYWORDS.contains(id)) return id.intern()
        throw error("Expected an identifier, found '" + id + "'")
    }

    private fun matchId(): String? {
        val old = pos()
        val id = _lexer!!.matchId()
        if (id == null) return null
        if (!KEYWORDS.contains(id)) return id
        pos(old)
        return null
    }

    // Require an exact match
    private fun require(syntax: String): Parser {
        require<Any?>(null, syntax)
        return this
    }

    private fun <N> require(n: N?, syntax: String): N? {
        if (match(syntax)) return n
        throw errorSyntax(syntax)
    }

    /**///////////////////////////////// */ // Lexer components
    // Lexer provides low level access to the raw file bytes, peeks and matches
    // short strings, parses numbers, skips comments and whitespace, tracks
    // line numbers, allows the parse position to be saved and restored, and
    // serves as a location indicator for errors.
    class Lexer {
        // Input buffer; an array of text bytes read from a file or a string
        private val _input: ByteArray

        // Tracks current position in input buffer
        private var _position = 0

        //
        private var _line_number = 1

        // Start of current line
        private var _line_start = 0

        /**
         * Record the source text for lexing
         */
        constructor(source: String) : this(source.getBytes())

        /**
         * Direct from disk file source
         */
        constructor(buf: ByteArray) {
            _input = buf
        }

        /**
         * Copy a lexer from a lexer
         */
        private constructor(l: Lexer) {
            _input = l._input
            _position = l._position
            _line_number = l._line_number
            _line_start = l._line_start
        }

        // Very handy in the debugger, shows the unparsed program
        override fun toString(): String {
            return String(_input, _position, _input.length - _position)
        }

        private val isEOF: Boolean
            // True if at EOF
            get() = _position >= _input.length

        // Peek next character, or report EOF
        private fun peek(): Char {
            return if (this.isEOF)
                Character.MAX_VALUE // Special value that causes parsing to terminate
            else Char(_input[_position].toUShort())
        }

        // Just crash if misused
        fun peek(off: Int): Byte {
            return _input[_position + off]
        }

        private fun inc() {
            if (_position++ < _input.length && _input[_position - 1] == '\n'.code.toByte()) {
                _line_number++
                _line_start = _position
            }
        }

        // Does not honor LF, so caller must roll back position on a LF
        private fun nextChar(): Char {
            val ch = peek()
            inc()
            return ch
        }

        private val isWhiteSpace: Boolean
            // True if a white space
            get() = peek() <= ' ' // Includes all the use space, tab, newline, CR

        /**
         * Return the next non-white-space character
         */
        private fun skipWhiteSpace() {
            while (true) {
                if (this.isWhiteSpace) inc()
                else if (_position + 2 < _input.length && _input[_position] == '/'.code.toByte() && _input[_position + 1] == '/'.code.toByte()) {
                    inc()
                    inc()
                    while (!this.isEOF && _input[_position] != '\n'.code.toByte()) inc()
                } else if (_position + 2 < _input.length && _input[_position] == '/'.code.toByte() && _input[_position + 1] == '*'.code.toByte()) {
                    // Skip /*comment*/
                    while (!this.isEOF && !(_input[_position - 1] == '*'.code.toByte() && _input[_position] == '/'.code.toByte())) inc()
                    inc()
                } else break
            }
        }

        // Next non-white-space character, or EOF
        fun nextXChar(): Char {
            skipWhiteSpace()
            return nextChar()
        }

        // Return true, if we find "syntax" after skipping white space; also
        // then advance the cursor past syntax.
        // Return false otherwise, and do not advance the cursor.
        fun match(syntax: String): Boolean {
            assert(
                syntax.indexOf('\n'.code) == -1 // No newlines in match
            )
            skipWhiteSpace()
            val len: Int = syntax.length()
            if (_position + len > _input.length) return false
            for (i in 0..<len) if (Char(_input[_position + i].toUShort()) != syntax.charAt(i)) return false
            _position += len
            return true
        }

        // Match must be exact and not followed by more ID characters.
        // Prevents identifier "ifxy" from matching an "if" statement.
        fun matchx(syntax: String): Boolean {
            if (!match(syntax)) return false
            if (!isIdLetter(peek())) return true
            _position -= syntax.length()
            return false
        }

        // Match this char, and the next char must be different.
        // Handles '&&' vs '&'
        fun matchOp(c0: Char): Boolean {
            skipWhiteSpace()
            if (_position + 1 >= _input.length || _input[_position] != c0.code.toByte() || _input[_position + 1] == c0.code.toByte()) return false
            inc()
            return true
        }

        // Match these two characters in a row
        fun matchOpx(c0: Char, c1: Char): Boolean {
            skipWhiteSpace()
            if (_position + 1 >= _input.length || _input[_position] != c0.code.toByte() || _input[_position + 1] == c1.code.toByte()) return false
            inc()
            return true
        }

        private fun peek(ch: Char): Boolean {
            skipWhiteSpace()
            return peek() == ch
        }

        fun peekIsId(): Boolean {
            skipWhiteSpace()
            return isIdStart(peek())
        }

        // Return an identifier or null
        fun matchId(): String {
            return (if (peekIsId()) parseId() else null)!!
        }

        val anyNextToken: String
            // Used for errors
            get() {
                if (this.isEOF) return ""
                if (isIdStart(peek())) return parseId()
                if (isNumber(peek())) return parseNumberString()
                if (isPunctuation(peek())) return parsePunctuation()
                return java.lang.String.valueOf(peek())
            }


        fun isNumber(ch: Char): Boolean {
            return Character.isDigit(ch)
        }

        // Return a constant Type, either TypeInteger or TypeFloat
        private fun parseNumber(): Type? {
            val old = _position
            val len = this.isLongOrDouble
            if (len > 0) {
                if (len > 1 && _input[old] == '0'.code.toByte()) throw error(
                    "Syntax error: integer values cannot start with '0'",
                    this
                )
                val i = Long.parseLong(String(_input, old, len))
                return TypeInteger.Companion.constant(i)
            }
            return TypeFloat.Companion.constant(Double.parseDouble(String(_input, old, -len)))
        }

        private fun parseNumberString(): String {
            val old = _position
            val len = Math.abs(this.isLongOrDouble)
            return String(_input, old, len)
        }

        private val isLongOrDouble: Int
            // Return +len that ends a long
            get() {
                val old = _position
                var c: Char
                while (Character.isDigit(nextChar().also { c = it }));
                if (!(c == 'e' || c == '.')) return --_position - old
                if (peek() == '-') nextChar()
                while (Character.isDigit(nextChar().also { c = it }) || c == 'e' || c == '.');
                return -(--_position - old)
            }

        // First letter of an identifier
        private fun isIdStart(ch: Char): Boolean {
            return Character.isAlphabetic(ch.code) || ch == '_'
        }

        // All characters of an identifier, e.g. "_x123"
        private fun isIdLetter(ch: Char): Boolean {
            return Character.isLetterOrDigit(ch) || ch == '_'
        }

        private fun parseId(): String {
            val start = _position
            while (isIdLetter(nextChar()));
            return String(_input, start, --_position - start)
        }

        //
        private fun isPunctuation(ch: Char): Boolean {
            return "=;[]<>()+-/*&|^".indexOf(ch.code) != -1
        }

        private fun parsePunctuation(): String {
            val start = _position
            return String(_input, start, 1)
        }

        // Next oper= character, or 0.
        // As a convenience, mark "++" as a char 1 and "--" as char -1 (65535)
        fun matchOperAssign(): Char {
            skipWhiteSpace()
            if (_position + 2 >= _input.length) return 0.toChar()
            val ch0 = Char(_input[_position].toUShort())
            if ("+-/*&|^".indexOf(ch0.code) == -1) return 0.toChar()
            val ch1 = Char(_input[_position + 1].toUShort())
            if (ch1 == '=') {
                _position += 2
                return ch0
            }
            if (isIdLetter(Char(_input[_position + 2].toUShort()))) return 0.toChar()
            if (ch0 == '+' && ch1 == '+') {
                _position += 2
                return 1.toChar()
            }
            if (ch0 == '-' && ch1 == '-') {
                _position += 2
                return -1.toChar()
            }
            return 0.toChar()
        }
    }

    fun errorSyntax(syntax: String?): ParseException {
        return _errorSyntax("expected " + syntax)
    }

    private fun _errorSyntax(msg: String?): ParseException {
        return error("Syntax error, " + msg + ": " + _lexer!!.anyNextToken)
    }

    fun error(msg: String?): ParseException {
        return error(msg, _lexer)
    }

    class ParseException internal constructor(msg: String?, loc: Lexer?) : RuntimeException(msg) {
        val _loc: Lexer?

        // file:line:charoff err
        //String msg = "src:"+_line_number+":"+(_position-_line_start)+" "+errorMessage;
        init {
            _loc = loc
        }
    }

    companion object {
        var ZERO: ConstantNode // Very common node, cached here
        var NIL: ConstantNode // Very common node, cached here
        var XCTRL: XCtrlNode? // Very common node, cached here

        /**
         * List of keywords disallowed as identifiers
         */
        @JvmField
        val KEYWORDS: HashSet<String?> = object : HashSet<String?>() {
            init {
                add("bool")
                add("break")
                add("byte")
                add("continue")
                add("else")
                add("f32")
                add("f64")
                add("false")
                add("flt")
                add("i16")
                add("i32")
                add("i64")
                add("i8")
                add("if")
                add("int")
                add("new")
                add("null")
                add("return")
                add("struct")
                add("true")
                add("u1")
                add("u16")
                add("u32")
                add("u8")
                add("while")
            }
        }

        // Mapping from a type name to a Type.  The string name matches
        // `type.str()` call.
        @JvmField
        var TYPES: HashMap<String?, Type?> = defaultTypes()

        fun defaultTypes(): HashMap<String?, Type?> {
            return object : HashMap<String?, Type?>() {
                init {
                    put("bool", TypeInteger.Companion.U1)
                    put("byte", TypeInteger.Companion.U8)
                    put("f32", TypeFloat.Companion.F32)
                    put("f64", TypeFloat.Companion.F64)
                    put("flt", TypeFloat.Companion.F64)
                    put("i16", TypeInteger.Companion.I16)
                    put("i32", TypeInteger.Companion.I32)
                    put("i64", TypeInteger.Companion.BOT)
                    put("i8", TypeInteger.Companion.I8)
                    put("int", TypeInteger.Companion.BOT)
                    put("u1", TypeInteger.Companion.U1)
                    put("u16", TypeInteger.Companion.U16)
                    put("u32", TypeInteger.Companion.U32)
                    put("u8", TypeInteger.Companion.U8)
                    put("val", Type.Companion.TOP) // Marker type, indicates type inference
                    put("var", Type.Companion.BOTTOM) // Marker type, indicates type inference
                }
            }
        }

        private val ALTMP = Ary<Node>(Node::class.java)
        fun memName(alias: Int): String {
            return ("$" + alias).intern()
        }

        fun con(con: kotlin.Long): Node {
            return if (con == 0L) ZERO else Companion.con(TypeInteger.Companion.constant(con))
        }

        fun con(t: Type?): ConstantNode {
            return ConstantNode(t).peephole() as ConstantNode
        }

        fun off(base: TypeStruct, fname: String?): ConFldOffNode? {
            return (ConFldOffNode(base._name, fname).peephole()) as ConFldOffNode?
        }

        // ch is +/- 1, means oper++ or oper-- means postfix
        private fun postfix(ch: Char): Boolean {
            return ch.code.toByte().toInt() == 1 || ch.code.toByte().toInt() == -1
        }

        fun error(msg: String?, loc: Lexer?): ParseException {
            return ParseException(msg, loc)
        }
    }
}
