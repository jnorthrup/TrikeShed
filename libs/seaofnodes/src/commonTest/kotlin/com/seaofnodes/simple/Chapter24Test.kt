package com.seaofnodes.simple


import kotlin.test.Test
import kotlin.test.assertEquals

class Chapter24Test {
    @Test
    @Throws(Exception::class)
    fun testJig() {
        val src = "int v0=0;  v0 = arg && 0== 0 !=0;"
        testCPU(src, "x86_64_v2", "Win64", -1, null)
        testCPU(src, "riscv", "SystemV", -1, null)
        testCPU(src, "arm", "SystemV", -1, null)
    }

    @Test
    @Throws(Exception::class)
    fun testOr() {
        val src =
            """
int a = 1;
int b = 0;

if(a++ || b++ ) {
    if(b == 0 && a == 2) {
        sys.io.p("Or");
    }
} else{
    sys.io.p("And");
}
return 0;

""".trimIndent()

        TestC.runSF("or1", src, null, "Or", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("or1", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("Or", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("or1", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("Or", arm._stdout.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testAnd() {
        val src =
            """
int a = 1;
int b = 1;

if(a && b) {
    sys.io.p("And");
} else {
    sys.io.p("Or");
}
return 0;

""".trimIndent()
        TestC.runSF("and1", src, null, "And", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("and1", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("And", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("and1", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("And", arm._stdout.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testAndPtr() {
        // Todo: have one src here
        val src =
            """
struct S { S? fld; };
val ptr = arg == 1 ? null : new S{fld = arg==1 ? null : new S{fld = null;};};
return ptr && ptr.fld ? "true" : "false";

""".trimIndent()
        val src2 =
            """
struct S { S? fld; };

val ptr = arg == 1 ? null : new S{fld = arg==1 ? null : new S{fld = null;};};
if( ptr && ptr.fld ) {
  sys.io.p("true");
} else {
  sys.io.p("false");
}

return 0;

""".trimIndent()
        val code: CodeGen? = CodeGen(src).parse().opto().typeCheck()
        assertEquals("[97-117][ 116,114,117,101]", Eval2.eval(code, 0))

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("ptrand", src2, 1, 8, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("false", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("ptrand", src2, 1, 8, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("false", arm._stdout.toString())
    }

    // conditional side effect
    @Test
    @Throws(IOException::class)
    fun testCondSideEffAnd() {
        val src =
            """
int a = 1;
int b = 1;

int x=1;
int y=1;
int z=0;

int g = x++ && y++ && z++;

if(x == 2 && y == 2 && z == 1 && g == 0) {
    sys.io.p("Effected");
} else {
    sys.io.p("Not effected");
}
return 0;

""".trimIndent()

        TestC.runSF("and2", src, null, "Effected", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("and2", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("Effected", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("and2", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("Effected", arm._stdout.toString())
    }


    @Test
    @Throws(IOException::class)
    fun testCondSideEffOr() {
        val src =
            """
int a = 1;
int b = 1;

int x = -1;
int y=1;
int z= -1;

int g = x++ || y++ || z++;

int switch = 0;
if(x == 4 || y == 4 || z == 4) {
    switch = -1;
} else {
    switch = 1;
}
int cd = 1;
if((x == 0 && y == 1 && z==-1) && switch && g) {
    sys.io.p("Effected");
} else {
    sys.io.p("Not effected");
}
return 0;

""".trimIndent()
        TestC.runSF("or2", src, null, "Effected", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("or2", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("Effected", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("or2", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("Effected", arm._stdout.toString())
    }

    // test it with function calls
    @Test
    @Throws(IOException::class)
    fun testFuncCall() {
        val src =
            """
// -*- mode: java;  -*-
int a = 1;
int b = 1;

var sq = { int x ->
    x*x;
};

if(a && sq(0)) {
    sys.io.p("And");
} else {
    sys.io.p("Or");
}
return 0;

""".trimIndent()
        TestC.runSF("and3", src, null, "Or", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("and3", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("Or", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("and3", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("Or", arm._stdout.toString())
    }

    // test it with function calls
    @Test
    @Throws(IOException::class)
    fun testStack1() {
        val src =
            """
int something_specific = 11;

if (10 < something_specific < 20) {
    sys.io.p("In Range");
} else {
   sys.io.p("Out of Range");
}
return 0;

""".trimIndent()

        TestC.runSF("stacked_r_1", src, null, "In Range", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_1", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("In Range", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_1", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("In Range", arm._stdout.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testStack2() {
        val src =
            """
int score = 75;

if (60 <= score < 90) {
    sys.io.p("In Range");
} else {
sys.io.p("Out of range");
}
return 0;

""".trimIndent()

        TestC.runSF("stacked_r_2", src, null, "In Range", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_2", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("In Range", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_2", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("In Range", arm._stdout.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testStack3() {
        val src =
            """
var sq = { int x ->
    x*x;
};

int score = 5;

val str = (sq(2) <= score < sq(3)) ? "In Range" : "Out of range";
sys.io.p(str);
return 0;

""".trimIndent()

        TestC.runSF("stacked_r_3", src, null, "In Range", 5)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_3", src, 0, 5, false)
        val trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("In Range", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_3", src, 0, 5, false)
        val trap_1: Int = arm.step(100)
        assertEquals(0, trap_1)
        assertEquals(0, arm.regs[0])
        assertEquals("In Range", arm._stdout.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testStack4() {
        val src =
            """
int something_specific = 9;

if (10 < something_specific < 20) {
    sys.io.p("In Range");
} else {
   sys.io.p("Out of Range");
}
return 0;

""".trimIndent()

        TestC.runSF("stacked_r_4", src, null, "Out of Range", 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_4", src, 0, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("Out of Range", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_4", src, 0, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("Out of Range", arm._stdout.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testStack5() {
        val src =
            """
var sq = { int x ->
    x*x;
};

int score = 5;

if (sq(2) <= score < sq(3)) {
sys.io.p("In Range");
} else {
sys.io.p("Out of range");
}

return 0;

""".trimIndent()
        TestC.runSF("stacked_r_5", src, null, "In Range", 5)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_5", src, 0, 7, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(0, R5.regs[riscv.A0])
        assertEquals("In Range", R5._stdout.toString())

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_5", src, 0, 7, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(0, arm.regs[0])
        assertEquals("In Range", arm._stdout.toString())
    }


    @Test
    fun testStack6() {
        try {
            CodeGen(
                """
                        return 0 < arg > 1;

                        """.trimIndent()
            ).parse()
            fail()
        } catch (e: RuntimeException) {
            assertEquals("Mixing relational directions in a chained relational test", e.getMessage())
        }
    }

    @Test
    @Throws(IOException::class)
    fun testStack9() {
        val stack9 = "1"
        val src =
            """
var stack9 = { int x ->
    (x < 1) == 1;
};

""".trimIndent()
        val src2 =
            """
return (0 < 1) == 1;

""".trimIndent()
        TestC.run(src, "stacked_r_9x", null, stack9, 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_9", src2, 0, 0, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(1, R5.regs[riscv.A0])

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_9", src2, 0, 0, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(1, arm.regs[0])
    }

    @Test
    @Throws(IOException::class)
    fun testStack10() {
        val stack10 = "1"
        val src =
            """
var stack10 = { int x ->
    (x < 1) == (1>x);
};

""".trimIndent()
        val src2 =
            """
return (0 < 1) == (1>0);

""".trimIndent()
        TestC.run(src, "stacked_r_10x", null, stack10, 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_10", src2, 0, 0, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(1, R5.regs[riscv.A0])

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_10", src2, 0, 0, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(1, arm.regs[0])
    }

    @Test
    @Throws(IOException::class)
    fun testStack11() {
        val stack11 = "1"
        val src =
            """
var stack11 = { int x ->
    0 < x < x+1 < 4;
};

""".trimIndent()
        val src2 =
            """
return 0 < arg < arg+1 < 4;

""".trimIndent()
        TestC.run(src, "stacked_r_11x", null, stack11, 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_11", src2, 1, 2, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(1, R5.regs[riscv.A0])

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_11", src2, 1, 2, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(1, arm.regs[0])
    }

    // shows that it is parsed as
    // ((0 != x) != 2)
    @Test
    @Throws(IOException::class)
    fun testStack12() {
        val stack11 = "1"
        val src =
            """
                var stack12 = { int x ->
                    0 != x != 2;
                };

                """.trimIndent()
        val src2 =
            """
                return 0 != arg != 2;

                """.trimIndent()
        TestC.run(src, "stacked_r_12x", null, stack11, 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_12", src2, 1, 0, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(1, R5.regs[riscv.A0])

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_12", src2, 1, 0, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(1, arm.regs[0])
    }


    // shows that it is parsed as
    // ((0 == x) == 1)
    @Test
    @Throws(IOException::class)
    fun testStack13() {
        val stack12 = "1"
        val src =
            """
                var stack13 = { int x ->
                    0 == x == 1;
                };

                """.trimIndent()
        val src2 =
            """
                return 0 == arg == 1;

                """.trimIndent()
        TestC.run(src, "stacked_r_13x", null, stack12, 0)

        // Evaluate on RISC5 emulator
        val R5: EvalRisc5 = TestRisc5.build("stacked_r_13", src2, 1, 0, false)
        var trap: Int = R5.step(100)
        assertEquals(0, trap)
        assertEquals(1, R5.regs[riscv.A0])

        // Evaluate on ARM emulator
        val arm: EvalArm64 = TestArm64.build("stacked_r_13", src2, 1, 0, false)
        trap = arm.step(100)
        assertEquals(0, trap)
        assertEquals(1, arm.regs[0])
    }

    @Test
    fun testFRefFields() {
        val src =
            """
// A hypothetical scanner class
struct Scan {
    int !x;
    u8[~] buf;
    // Skip whitespace
    val skip = { Scan s ->
        while( s.buf[s.x] <= ' ' )
            s.x++;
    };
    // Peek a character; if matched consume it, else false.
    val peek = { Scan s, u8 c ->
        skip(s);
        if( s.buf[s.x] != c ) return false;
        s.x++;
        return true;
    };
};
Scan !s = new Scan{ buf = "  q"; };
return Scan.peek(s,'q');

""".trimIndent()

        val code: CodeGen = CodeGen(src).parse().opto().typeCheck()
        assertEquals("return Phi(Region,0,1);", code._stop.toString())
        assertEquals("1", Eval2.eval(code, 0))
        testCPU(src, "x86_64_v2", "Win64", 17, null)
    }

    @Test
    fun testFRefFields2() {
        val src =
            """
// A hypothetical scanner class
struct Scan {
    int !x;
    u8[~] buf;
    // Skip whitespace
    val skip = { ->
        while( buf[x] <= ' ' )
            x++;
        return self;
    };
};
val s = new Scan{ buf = "  q"; };
return s.skip().x;

""".trimIndent()

        try {
            CodeGen(src).parse().opto().typeCheck()
            fail()
        } catch (e: Exception) {
            assertEquals(
                "Argument #0 isa *Scan {i64 x; *[]u8 buf; { *Scan -> *Scan {i64 !x; *[]u8 buf; {21} skip; } #21} skip; }, but must be a *Scan {i64 !x; *[]u8 buf; ... }",
                e.getMessage()
            )
        }
    }


    @Test
    fun testMethod() {
        val src =
            """
// A hypothetical scanner class
struct Scan {
    int !x;
    u8[~] buf;
    // Skip whitespace
    val skip = { ->
        while( buf[x] <= ' ' )
            x++;
    };
    val require = { u8 ch ->
        skip();
        buf[x++]==ch;
    };
};
Scan !s = new Scan{ buf = "  [1,2]"; };
return s.require('[');

""".trimIndent()

        val code: CodeGen = CodeGen(src).parse().opto().typeCheck()
        assertEquals("return (.[]==91);", code._stop.toString())
        assertEquals("1", Eval2.eval(code, 0))
        testCPU(src, "x86_64_v2", "Win64", 16, null)
    }

    @Test
    fun testSCCP0() {
        val src =
            """
int x = 1;
int cnt = 5;
while (cnt) {
  cnt -= 1;
  if (x == 0) {
    x += 1;
  }
}
return x;

""".trimIndent()
        val code: CodeGen = CodeGen(src).parse().opto().typeCheck()
        assertEquals("return 1;", code._stop.toString())
    }

    @Test
    fun testSCCP1() {
        val src =
            """
int x = 1;
int cnt = 5;
while( cnt-- )
  if( x == 1 )
    x = 2-x;
return x;

""".trimIndent()
        val code: CodeGen = CodeGen(src).parse().opto().typeCheck()
        assertEquals("return 1;", code._stop.toString())
    }

    companion object {
        fun testCPU(src: String?, cpu: String?, os: String?, spills: Int, stop: String?): CodeGen {
            val code: CodeGen = CodeGen(src).driver(CodeGen.Phase.Encoding, cpu, os)
            var delta = spills shr 3
            if (delta == 0) delta = 1
            if (spills != -1) assertEquals("Expect spills:", spills, code._regAlloc._spillScaled, delta)
            if (stop != null) assertEquals(stop, code._stop.toString())
            return code
        }
    }
}
