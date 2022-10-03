#!/bin/bash

tupleNounsUS=( "Nonce"  "Mono" "Pair" "Triple"  "Quad"  "Quint"  "Set"  "Sept"  "Oct"  "Non"  "Dec"  "Undec"  "Duodec"  "Tredec"  "Quattuordec"  "Quindec"  "Sexdec"  "Septendec"  "Octodec"  "Novemdec"  "Vigint"  "Unvigint"  "Duovigint")

# This generates a descriptive Kotlin tuple interface to stdout according to the template below with Quad, Quint, Etc up to 22

cat >/dev/null <<EOF
/**
 * Joins 4 things - a Quad tuple
 */

interface Join4<A1, A2, A3, A4, > {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4

    infix operator fun get (index: Int): Any? {
        require(index in 0..3) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            2 -> a3
            3 -> a4
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, > invoke(a1: A1, a2: A2, a3: A3, a4: A4, ) = object : Join4<A1, A2, A3, A4, > {
            override val a1 get() = a1
            override val a2 get() = a2
            override val a3 get() = a3
            override val a4 get() = a4
        }
    }
}
//Tuple4 toString
fun <A1, A2, A3, A4, > Join4<A1, A2, A3, A4, >.toString(): String = "($a1, $a2, $a3, $a4, )"

//Tuple4 Series using s_
val <A1, A2, A3, A4, > Join4<A1, A2, A3, A4, >.\`⮞\` get() = s_[this.a1, this.a2, this.a3, this.a4, ].\`⮞\`
EOF



for i in $(seq 2 23); do
    tname=${tupleNounsUS[$i]}
    echo
    echo "/**"
    echo " * Joins $i things - a ${tupleNounsUS[$i]} tuple"
    echo " */"
    echo "interface Join$i<$(for j in $(seq 1 $i); do echo -n "A$j, "; done)> {"

    for j in $(seq 1 $i); do
        echo "    val a$j: A$j"
    done

    for j in $(seq 1 $i); do
        echo "    operator fun component$j(): A$j = a$j"
    done

    echo "    infix operator fun get (index: Int): Any? {"
    echo "        require(index in 0..$((i-1))) { \"index out of bounds\" }"
    echo "        return when (index) {"
    for j in $(seq 0 $((i-1))); do
        echo "            $j -> a$((j+1))"
    done
    echo "            else -> throw IndexOutOfBoundsException()"
    echo "        }"
    echo "    }"

    echo "    companion object {"
    echo "        operator fun <$(for j in $(seq 1 $i); do echo -n "A$j, "; done)> invoke($(for j in $(seq 1 $i); do echo -n "a$j: A$j, "; done)) = object : Join$i<$(for j in $(seq 1 $i); do echo -n "A$j, "; done)> {"
    for j in $(seq 1 $i); do
        echo "            override val a$j get() = a$j"
    done
    echo "        }"
    echo "    }"
    echo "}"
    echo "//Tuple$i toString"
    echo "fun <$(for j in $(seq 1 $i); do echo -n "A$j, "; done)> Join$i<$(for j in $(seq 1 $i); do echo -n "A$j, "; done)>.toString(): String = \"${tname}Join$i:($(for j in $(seq 1 $i); do echo -n "\$a$j, "; done))\""
    echo
    echo "//Tuple$i Series using s_"
    echo "val <$(for j in $(seq 1 $i); do echo -n "A$j, "; done)> Join$i<$(for j in $(seq 1 $i); do echo -n "A$j, "; done)>.\`⮞\` get() = s_[$(for j in $(seq 1 $i); do echo -n "a$((j)), "; done)].\`⮞\`"
    echo
done
