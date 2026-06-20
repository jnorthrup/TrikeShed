package borg.trikeshed.forge.demo

import borg.trikeshed.forge.*
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import kotlinx.coroutines.runBlocking

/**
 * Forge Overlay Demo - demonstrates graph labels and overlays with ACL
 */
fun main() = runBlocking {
    println("🏷️ Forge Overlay Demo Starting...\n")
    
    // Demo 1: Overlay with Codex agent principal
    println("1. Codex Agent Overlays")
    val codexOverlays = forgeKanbanOverlayDemo(AgentType.CODEX.asOverlayPrincipal())
    println(codexOverlays)
    
    // Demo 2: Overlay with Generic agent principal
    println("\n2. Generic Agent Overlays")
    val genericOverlays = forgeKanbanOverlayDemo(AgentType.GENERIC.asOverlayPrincipal())
    println(genericOverlays)
    
    // Demo 3: Overlay with User owner principal
    println("\n3. User Owner Overlays")
    val ownerPrincipal = ForgeOverlayPrincipalKind.USER j ForgePrincipalId("owner")
    val ownerOverlays = forgeKanbanOverlayDemo(ownerPrincipal)
    println(ownerOverlays)
    
    // Demo 4: Default permissions
    println("\n4. Default Permissions")
    val perms = defaultOverlayPermissions()
    val permList = listOf(
        OverlayPermission.READ_LABEL,
        OverlayPermission.DRILLDOWN,
        OverlayPermission.QUERY_GROUP,
    )
    println("   Available permissions: ${permList.size}")
    for (p in permList) {
        println("   - $p")
    }
    
    // Demo 5: Custom ACL
    println("\n5. Custom ACL Construction")
    val userPrincipal = ForgeOverlayPrincipalKind.USER j ForgePrincipalId("demo-user")
    val customAcl = graphOverlayAcl(
        principal = userPrincipal,
        permissions = s_[
            OverlayPermission.READ_LABEL,
            OverlayPermission.DRILLDOWN,
        ]
    )
    println("   ACL created with ${permList.size} permissions")
    
    println("\n✅ Forge Overlay Demo Complete!")
}