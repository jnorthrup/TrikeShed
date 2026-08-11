cat << 'DIFF' > patch_final8.diff
--- src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt
+++ src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt
@@ -336,8 +336,10 @@
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
patch -p0 < patch_final8.diff
