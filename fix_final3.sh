<<<<<<< ours
cat << 'DIFF' > patch_final3.diff -    private suspend fun routeHttp(payload: ByteArray): HttpResponse {-            writeStringJvm(tmp, payload)patch -p0 < patch_final3.diffDIFF                 graphIndex.addOrGet(node)                 causalWal.append(node.causalKey, JsonSupport.stringify(node.toWalMap()).encodeToByteArray())             reduction.causalNodes.forEach { node ->+            }+                ForgeKanbanIngest.persistMarkdown("jim", tmp)+                writeStringJvm(tmp, payload)+            val reduction = kotlinx.coroutines.withContext(Dispatchers.IO) {-            val reduction = ForgeKanbanIngest.persistMarkdown("jim", tmp)             val tmp = "/tmp/hi"         return runCatching {         if (payload.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")@@ -288,8 +336,10 @@         JsonSupport.stringify(         val reduction = ForgeKanbanIngest.load("jim")     private fun boardJson(): String = runCatching {++    }+        }.getOrElse { HttpResponse(500, """{"error":"invoke_failed","reason":"${it.message}"}""") }+            HttpResponse(202, """{"accepted":true}""")+            fanout.dispatch(nuid, requestPayload)++            val requestPayload = java.util.Base64.getDecoder().decode(requestPayloadBase64)+            val requestPayloadBase64 = obj["payload"] as String++            val nuid = nuid(cap, nonce, subnet)+            val subnet = Subnet.parse(subnetStr)+            val nonce = if (nonceDerivedKey != null) Nonce.Derived(nonceDerivedKey) else Nonce.Restored(nonceBytes)+            val nonceBytes = java.util.Base64.getDecoder().decode(nonceBytesBase64)++            }+                else -> Capability.Custom(capabilityCat, capabilityToken ?: "")+                }+                    Capability.Custom(parts[0], parts.getOrElse(1) { "" })+                    val parts = (capabilityToken ?: ":").split(":", limit = 2)+                "custom" -> {+                "blackboard" -> Capability.BlackBoard+                "modelmux" -> Capability.Model+                "sctp" -> Capability.Sctp+                "wireproto" -> Capability.Wireproto(capabilityToken ?: "")+                "cas" -> Capability.Cas(capabilityToken ?: "")+                "process" -> Capability.Process(capabilityToken ?: "")+            val cap = when (capabilityCat) {+            +            val subnetStr = nuidObj["subnet"] as String+            val nonceDerivedKey = nuidObj["nonceDerivedKey"] as? String+            val nonceBytesBase64 = nuidObj["nonceBytes"] as String+            val capabilityToken = nuidObj["capabilityToken"] as? String+            val capabilityCat = nuidObj["capabilityCat"] as String+            val nuidObj = obj["nuid"] as Map<String, Any?>+            @Suppress("UNCHECKED_CAST")+            val obj = JsonSupport.parse(body) as Map<String, Any?>+            @Suppress("UNCHECKED_CAST")++            if (body.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")+            val body = text.substringAfter("\r\n\r\n", "").ifEmpty { text.substringAfter("\n\n", "") }+            val text = String(payload, StandardCharsets.UTF_8)+        return runCatching {+    private suspend fun apiInvoke(payload: ByteArray, fanout: NuidFanoutElement): HttpResponse {      }         }             else           -> HttpResponse(404, """{"error":"not_found","path":"$path"}""")+            "/"           -> HttpResponse(200, borg.trikeshed.forge.ForgeApp.renderHtml(), "text/html; charset=utf-8")+            }+                if (manifestBytes != null) HttpResponse(200, manifestBytes.decodeToString(), "application/manifest+json; charset=utf-8") else HttpResponse(404, """{"error":"not_found"}""")+                val manifestBytes = javaClass.getResourceAsStream("/web/manifest.webmanifest")?.readBytes()+            "/manifest.webmanifest" -> {+            "/api/invoke" -> if (method == "POST") apiInvoke(payload, fanout) else HttpResponse(405, """{"error":"method_not_allowed"}""")-            "/"           -> HttpResponse(200, "<html><body>Forge litebike listener — see /api/health</body></html>", "text/html; charset=utf-8")             "/api/donor"  -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")             "/api/submit" -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")             "/api/board"  -> HttpResponse(200, boardJson())@@ -257,11 +259,57 @@         val parts = firstLine.split(' ')         val firstLine = text.lineSequence().firstOrNull() ?: ""         val text = String(payload, StandardCharsets.UTF_8)+    private suspend fun routeHttp(payload: ByteArray, fanout: NuidFanoutElement): HttpResponse {     // ── routes (single worker, hand-rolled) ────────────────────────────── @@ -245,7 +247,7 @@                     append("Content-Length: ${resp.body.toByteArray(StandardCharsets.UTF_8).size}\r\n")                     append("HTTP/1.1 ${resp.status} ${statusReason(resp.status)}\r\n")                 val out = buildString {+                val resp = routeHttp(payload, fanout)-                val resp = routeHttp(payload)                  fanout.dispatch(wireNuid, payload)                 val wireNuid = nuid(Capability.Wireproto("http"), Nonce.RandomBytes(), Subnet.lanLocalhost)@@ -206,7 +208,7 @@                 }                     donorPath                 } else {+                    }+                        tmp.toString()+                        NioFiles.writeString(tmp, md)+                        val tmp = NioFiles.createTempFile("tika-donor", ".md")+                        val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donor)+                    kotlinx.coroutines.withContext(Dispatchers.IO) {-                    tmp.toString()-                    NioFiles.writeString(tmp, md)-                    val tmp = NioFiles.createTempFile("tika-donor", ".md")-                    val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donor)                     // (tika4all tweaked config: Tesseract OCR + ffmpeg preprocessing).                     // Non-markdown donor (PDF/DOCX/image) — extract text via Tika                 val ingestPath = if (borg.trikeshed.kanban.JvmTikaIngestAdapter.isTikaCandidate(donor)) {@@ -172,10 +172,12 @@--- src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt
=======
cat << 'DIFF' > patch_final3.diff
--- src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt
+++ src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt
@@ -172,10 +172,12 @@
                 val ingestPath = if (borg.trikeshed.kanban.JvmTikaIngestAdapter.isTikaCandidate(donor)) {
                     // Non-markdown donor (PDF/DOCX/image) — extract text via Tika
                     // (tika4all tweaked config: Tesseract OCR + ffmpeg preprocessing).
-                    val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donor)
-                    val tmp = NioFiles.createTempFile("tika-donor", ".md")
-                    NioFiles.writeString(tmp, md)
-                    tmp.toString()
+                    kotlinx.coroutines.withContext(Dispatchers.IO) {
+                        val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donor)
+                        val tmp = NioFiles.createTempFile("tika-donor", ".md")
+                        NioFiles.writeString(tmp, md)
+                        tmp.toString()
+                    }
                 } else {
                     donorPath
                 }
@@ -206,7 +208,7 @@
                 val wireNuid = nuid(Capability.Wireproto("http"), Nonce.RandomBytes(), Subnet.lanLocalhost)
                 fanout.dispatch(wireNuid, payload)

-                val resp = routeHttp(payload)
+                val resp = routeHttp(payload, fanout)
                 val out = buildString {
                     append("HTTP/1.1 ${resp.status} ${statusReason(resp.status)}\r\n")
                     append("Content-Length: ${resp.body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
@@ -245,7 +247,7 @@

     // ── routes (single worker, hand-rolled) ──────────────────────────────

-    private suspend fun routeHttp(payload: ByteArray): HttpResponse {
+    private suspend fun routeHttp(payload: ByteArray, fanout: NuidFanoutElement): HttpResponse {
         val text = String(payload, StandardCharsets.UTF_8)
         val firstLine = text.lineSequence().firstOrNull() ?: ""
         val parts = firstLine.split(' ')
@@ -257,11 +259,57 @@
             "/api/board"  -> HttpResponse(200, boardJson())
             "/api/submit" -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
             "/api/donor"  -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
-            "/"           -> HttpResponse(200, "<html><body>Forge litebike listener — see /api/health</body></html>", "text/html; charset=utf-8")
+            "/api/invoke" -> if (method == "POST") apiInvoke(payload, fanout) else HttpResponse(405, """{"error":"method_not_allowed"}""")
+            "/manifest.webmanifest" -> {
+                val manifestBytes = javaClass.getResourceAsStream("/web/manifest.webmanifest")?.readBytes()
+                if (manifestBytes != null) HttpResponse(200, manifestBytes.decodeToString(), "application/manifest+json; charset=utf-8") else HttpResponse(404, """{"error":"not_found"}""")
+            }
+            "/"           -> HttpResponse(200, borg.trikeshed.forge.ForgeApp.renderHtml(), "text/html; charset=utf-8")
             else           -> HttpResponse(404, """{"error":"not_found","path":"$path"}""")
         }
     }

+    private suspend fun apiInvoke(payload: ByteArray, fanout: NuidFanoutElement): HttpResponse {
+        return runCatching {
+            val text = String(payload, StandardCharsets.UTF_8)
+            val body = text.substringAfter("\r\n\r\n", "").ifEmpty { text.substringAfter("\n\n", "") }
+            if (body.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")
+
+            @Suppress("UNCHECKED_CAST")
+            val obj = JsonSupport.parse(body) as Map<String, Any?>
+            @Suppress("UNCHECKED_CAST")
+            val nuidObj = obj["nuid"] as Map<String, Any?>
+            val capabilityCat = nuidObj["capabilityCat"] as String
+            val capabilityToken = nuidObj["capabilityToken"] as? String
+            val nonceBytesBase64 = nuidObj["nonceBytes"] as String
+            val nonceDerivedKey = nuidObj["nonceDerivedKey"] as? String
+            val subnetStr = nuidObj["subnet"] as String
+
+            val cap = when (capabilityCat) {
+                "process" -> Capability.Process(capabilityToken ?: "")
+                "cas" -> Capability.Cas(capabilityToken ?: "")
+                "wireproto" -> Capability.Wireproto(capabilityToken ?: "")
+                "sctp" -> Capability.Sctp
+                "modelmux" -> Capability.Model
+                "blackboard" -> Capability.BlackBoard
+                "custom" -> {
+                    val parts = (capabilityToken ?: ":").split(":", limit = 2)
+                    Capability.Custom(parts[0], parts.getOrElse(1) { "" })
+                }
+                else -> Capability.Custom(capabilityCat, capabilityToken ?: "")
+            }
+
+            val nonceBytes = java.util.Base64.getDecoder().decode(nonceBytesBase64)
+            val nonce = if (nonceDerivedKey != null) Nonce.Derived(nonceDerivedKey) else Nonce.Restored(nonceBytes)
+            val subnet = Subnet.parse(subnetStr)
+            val nuid = nuid(cap, nonce, subnet)
+
+            val requestPayloadBase64 = obj["payload"] as String
+            val requestPayload = java.util.Base64.getDecoder().decode(requestPayloadBase64)
+
+            fanout.dispatch(nuid, requestPayload)
+            HttpResponse(202, """{"accepted":true}""")
+        }.getOrElse { HttpResponse(500, """{"error":"invoke_failed","reason":"${it.message}"}""") }
+    }
+
     private fun boardJson(): String = runCatching {
         val reduction = ForgeKanbanIngest.load("jim")
         JsonSupport.stringify(
@@ -288,8 +336,10 @@
         if (payload.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")
         return runCatching {
             val tmp = "/tmp/hi"
-            writeStringJvm(tmp, payload)
-            val reduction = ForgeKanbanIngest.persistMarkdown("jim", tmp)
+            val reduction = kotlinx.coroutines.withContext(Dispatchers.IO) {
+                writeStringJvm(tmp, payload)
+                ForgeKanbanIngest.persistMarkdown("jim", tmp)
+            }
             reduction.causalNodes.forEach { node ->
                 causalWal.append(node.causalKey, JsonSupport.stringify(node.toWalMap()).encodeToByteArray())
                 graphIndex.addOrGet(node)
DIFF
patch -p0 < patch_final3.diff
>>>>>>> theirs
