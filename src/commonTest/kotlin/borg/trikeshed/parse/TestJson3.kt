package borg.trikeshed.parse

import kotlin.test.Test

class TestJson3 {
    @Test
    fun test() {
        val json = """
            {
                "name": "John Doe",
                "age": 43,
                "phones": [
                    "+44 1234567",
                    "+44 2345678"
                ],
                "address": {
                    "street": "Downing Street",
                    "city": "London"
                }
            }
        """.trimIndent()
//        val res = json.parseJson()
//        println(res)
//        println(res["phones"])
//        println(res["phones"]!![0])
//        println(res["address"]!!["city"])
//        println(res["address"]!!["city"]!!.reify)
    }
}