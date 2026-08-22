import sys

def run():
    with open("src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt", "r") as f:
        content = f.read()
    
    old_code = """        val channel = borg.trikeshed.pijul.PijulChannel()
        val fileOps = JvmFileOperations()

        // 1. Collect all valid touched paths
        val validArms = arms.filter { arm ->
            val touched = parsePatchFiles(arm.patch).filterNot(::isUnsafeAutomaticPatchPath)
            touched.isNotEmpty()
        }
        if (validArms.size <= 1) return null

        val allTouched = validArms.flatMap { parsePatchFiles(it.patch).filterNot(::isUnsafeAutomaticPatchPath) }.distinct()"""
    
    new_code = """        val channel = borg.trikeshed.pijul.PijulChannel()
        val fileOps = JvmFileOperations()

        val decoder = borg.trikeshed.userspace.containment.StigmergicProtocolDecoder()
        val suspiciousArms = mutableListOf<Arm>()

        // 1. Collect all valid touched paths
        val validArms = arms.filter { arm ->
            val touched = parsePatchFiles(arm.patch).filterNot(::isUnsafeAutomaticPatchPath)
            if (touched.isEmpty()) return@filter false
            
            val patches = touched.mapIndexed { index, path ->
                borg.trikeshed.userspace.containment.PatchData(
                    fileName = path.substringAfterLast('/'),
                    filePath = path,
                    content = if (index == 0) arm.patch else ""
                )
            }
            val detection = decoder.decode(patches)
            if (detection.isSuspicious) {
                println("[FLYWHEEL] QUARANTINE ${arm.session.id.takeLast(6)}: ${detection.protocolName} - ${detection.evidence}")
                drainFail(arm.session, "quarantined by StigmergicProtocolDecoder: ${detection.protocolName}")
                suspiciousArms.add(arm)
                false
            } else {
                true
            }
        }

        arms.removeAll(suspiciousArms)

        if (validArms.size <= 1) return null

        val allTouched = validArms.flatMap { parsePatchFiles(it.patch).filterNot(::isUnsafeAutomaticPatchPath) }.distinct()"""
    
    if old_code in content:
        content = content.replace(old_code, new_code)
        with open("src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt", "w") as f:
            f.write(content)
        print("Success")
    else:
        print("Old code not found")

run()
