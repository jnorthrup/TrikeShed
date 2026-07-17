package borg.trikeshed.job.schema

@JsFun("""
function(path) {
    const fs = require('fs');
    const buffer = fs.readFileSync(path);
    let result = [];
    for(let i=0; i<buffer.length; i++) {
        result.push(buffer[i]);
    }
    return JSON.stringify(result);
}
""")
private external fun nodeReadFileSyncArray(path: String): String

actual fun loadConfixSchemaBytes(path: String): ByteArray {
    val realPath = path.replace("classpath:/", "src/commonMain/resources/")
    val str = nodeReadFileSyncArray(realPath)
    // simple json parse to bytes
    val s = str.substring(1, str.length - 1) // remove [ ]
    if (s.isEmpty()) return ByteArray(0)
    val parts = s.split(",")
    return ByteArray(parts.size) { i -> parts[i].trim().toByte() }
}
