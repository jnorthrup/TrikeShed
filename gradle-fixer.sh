sed -i 's/implementation("org.bereft:TrikeShed-jvm:1.0")/implementation(project(":"))/' libs/classfile/lib_cursor/build.gradle.kts
sed -i 's/compileOnly("org.xvm:annotations:0.1.0-SNAPSHOT")/\/\/ compileOnly("org.xvm:annotations:0.1.0-SNAPSHOT")/' libs/classfile/lib_cursor/build.gradle.kts
