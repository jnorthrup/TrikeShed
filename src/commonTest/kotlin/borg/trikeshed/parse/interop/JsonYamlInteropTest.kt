package borg.trikeshed.parse.interop

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.yaml.YamlParser
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonYamlInteropTest {
    @Test
    fun jsonAndYamlReifySameNestedDocument() {
        val json =
            """{"openapi":"3.1.0","info":{"title":"Example API","version":"1.0.0"},"tags":["pets","orders"],"paths":{"/pets":{"get":{"operationId":"listPets"}}},"servers":[{"url":"https://api.example.com","description":"prod"}],"enabled":true,"count":7,"ratio":157.0,"emptyList":[],"emptyMap":{},"nothing":null}"""

        val yaml =
            """
            openapi: "3.1.0"
            info:
              title: "Example API"
              version: "1.0.0"
            tags:
              - "pets"
              - "orders"
            paths:
              /pets:
                get:
                  operationId: "listPets"
            servers:
              - url: "https://api.example.com"
                description: "prod"
            enabled: true
            count: 7
            ratio: 157.0
            emptyList: []
            emptyMap: {}
            nothing: null
            """.trimIndent()

        assertEquals(JsonSupport.parse(json), YamlParser.reify(yaml))
    }
}
