package borg.trikeshed.lib.parser.simple

import kotlin.test.*


class chgroup_Test
{
    @Test
    fun testWord()    {
        val input: CharSeries    =CharSeries("1234567890")
        val analog= input.clone()
        analog.get
        val result = chgroup_.digit(input)
        println(result)
        assertEquals(result, analog)

// what is the point of diminshing returns to linear-search for a char in a char array before binarysearch is faster?
    }
}