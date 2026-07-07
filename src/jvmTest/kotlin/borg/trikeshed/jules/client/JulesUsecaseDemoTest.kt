package borg.trikeshed.jules.client

import borg.trikeshed.jules.client.demo.JulesUsecaseDemo
import kotlin.test.Test
import kotlin.test.assertTrue

class JulesUsecaseDemoTest {

    @Test
    fun demoRunsEndToEndWithoutExceptions() {
        var completed = false
        try {
            JulesUsecaseDemo.main(emptyArray())
            completed = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        assertTrue(completed, "Demo did not complete successfully")
    }
}
