package borg.trikeshed.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Helper to attach the JvmPointcutAgent to the current JVM or a target JVM.
 */
public class AgentAttacher {

    /**
     * Attach agent to current JVM programmatically (requires JDK, not just JRE)
     */
    public static void attachToCurrentJvm(String agentJarPath) throws Exception {
        System.err.println("[AgentAttacher] Attaching agent to current JVM: " + agentJarPath);
        
        // Get current process PID
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.err.println("[AgentAttacher] Current PID: " + pid);

        // Load the VirtualMachine class
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
        
        // Load agent
        vmClass.getMethod("loadAgent", String.class).invoke(vm, agentJarPath);
        System.err.println("[AgentAttacher] Agent loaded successfully");
        
        // Detach
        vmClass.getMethod("detach").invoke(vm);
    }

    /**
     * Get the path to the built agent JAR
     */
    public static String findAgentJar() {
        // Try multiple locations
        String[] candidates = {
            "libs/jvm-agent/build/libs/jvm-agent.jar",
            "build/libs/jvm-agent.jar",
            System.getProperty("user.dir") + "/libs/jvm-agent/build/libs/jvm-agent.jar"
        };
        
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}