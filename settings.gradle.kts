pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

include(":libs:common")
include(":libs:forge")
include(":libs:forge-ui")
include(":libs:lcnc")
include(":libs:polyglot")
include(":libs:polyglot-bench")
include(":libs:asclepius")
include(":libs:motion-estimation")
include(":libs:classfile")
include(":libs:acpmcp")
include(":libs:couch")
include(":libs:couch:viewserver")
include(":libs:tiny-btrfs")
include(":libs:lsm")
include(":libs:patl")
include(":libs:tls")
include(":libs:quic")
include(":libs:kursive")
include(":libs:miniduck")
include(":libs:htx-client")
include(":libs:ngsctp")
include(":libs:concurrency")
include(":libs:activejs")
include(":libs:uring")
include(":libs:ipfs")
include(":libs:torrent")
include(":libs:tcpd")
include(":libs:modelmux")