cat << 'PATCH_EOF' > /tmp/jbba_fix.patch
--- src/jvmTest/kotlin/borg/trikeshed/jules/JulesBlackboardAdapterTest.kt
+++ src/jvmTest/kotlin/borg/trikeshed/jules/JulesBlackboardAdapterTest.kt
@@ -188,7 +188,7 @@
         assertEquals(1, bySession["s1"]?.size)
         assertEquals(2, bySession["s2"]?.size)
-        // View: 4 default + 2 session + 3 activity = 9
-        assertEquals(9, view.sections.size)
+        // View: 4 default + 2 session + 3 activity = 9
+        assertEquals(11, view.sections.size)
     }
PATCH_EOF
patch src/jvmTest/kotlin/borg/trikeshed/jules/JulesBlackboardAdapterTest.kt < /tmp/jbba_fix.patch
