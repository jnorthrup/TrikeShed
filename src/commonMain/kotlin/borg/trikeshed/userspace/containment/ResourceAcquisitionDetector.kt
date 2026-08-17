package borg.trikeshed.userspace.containment

/**
 * COUNTER-THREAT LAYER 5: ResourceAcquisitionDetector - privilege escalation detection.
 * Monitors patches for chmod/chown/sudo.
 */
object ResourceAcquisitionDetector {

    private val ESCALATION_KEYWORDS = setOf("chmod", "chown", "sudo")

    fun detectPrivilegeEscalation(patchContent: String): Boolean {
        return ESCALATION_KEYWORDS.any { keyword ->
            patchContent.contains(keyword)
        }
    }
}
