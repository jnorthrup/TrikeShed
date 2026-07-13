export JAVA_HOME=/home/jules/.sdkman/candidates/java/25.0.2-graalce
export PATH=$JAVA_HOME/bin:$PATH
./gradlew jsNodeTest --tests "borg.trikeshed.dht.NUIDTest" -PignoreDeps -x compileTestKotlinJs -x kotlinStoreYarnLock -x kmpPartiallyResolvedDependenciesChecker
