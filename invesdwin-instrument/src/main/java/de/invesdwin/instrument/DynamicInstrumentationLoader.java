package de.invesdwin.instrument;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.io.IOUtils;
import org.springframework.instrument.classloading.InstrumentationLoadTimeWeaver;

import de.invesdwin.instrument.internal.DynamicInstrumentationAgent;
import de.invesdwin.instrument.internal.JdkFilesFinder;

@ThreadSafe
public final class DynamicInstrumentationLoader {

    private static volatile Throwable threadFailed;

    private DynamicInstrumentationLoader() {}

    public static boolean isInitialized() {
        return InstrumentationLoadTimeWeaver.isInstrumentationAvailable();
    }

    public static void waitForInitialized() {
        try {
            while (!isInitialized() && threadFailed == null) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
            if (threadFailed != null) {
                throw new RuntimeException(threadFailed);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static {
        if (!isInitialized()) {
            try {
                final File tempAgentJar = createTempAgentJar();
                final String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
                final String pid = nameOfRunningVM.substring(0, nameOfRunningVM.indexOf('@'));
                final Thread loadAgentThread = new Thread() {

                    @Override
                    public void run() {
                        try {
                            //use reflection since tools.jar has been added to the classpath dynamically
                            final Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
                            final Object vm = vmClass.getMethod("attach", String.class).invoke(null,
                                    String.valueOf(pid));
                            vmClass.getMethod("loadAgent", String.class).invoke(vm, tempAgentJar.getAbsolutePath());
                        } catch (final Throwable e) {
                            threadFailed = e;
                            throw new RuntimeException(e);
                        }
                    }
                };

                DynamicInstrumentationReflections.addPathToSystemClassLoader(tempAgentJar);

                final JdkFilesFinder jdkFilesFinder = new JdkFilesFinder();
                final File toolsJar = jdkFilesFinder.findToolsJar();
                DynamicInstrumentationReflections.addPathToSystemClassLoader(toolsJar);

                final File attachLib = jdkFilesFinder.findAttachLib();
                DynamicInstrumentationReflections.addPathToJavaLibraryPath(attachLib.getParentFile());

                loadAgentThread.start();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }

        }
    }

    /**
     * Creates a new jar that only contains the DynamicInstrumentationAgent class.
     */
    private static File createTempAgentJar() throws Exception {
        final String agentClassName = DynamicInstrumentationAgent.class.getName();
        final File tempAgentJar = new File(DynamicInstrumentationProperties.TEMP_DIRECTORY, agentClassName + ".jar");
        final Manifest manifest = new Manifest(
                DynamicInstrumentationLoader.class.getResourceAsStream("/META-INF/MANIFEST.MF"));
        manifest.getMainAttributes().putValue("Premain-Class", agentClassName);
        manifest.getMainAttributes().putValue("Agent-Class", agentClassName);
        manifest.getMainAttributes().putValue("Can-Redefine-Classes", String.valueOf(true));
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", String.valueOf(true));
        final JarOutputStream tempAgentJarOut = new JarOutputStream(new FileOutputStream(tempAgentJar), manifest);
        final ZipEntry entry = new ZipEntry(agentClassName.replace(".", "/") + ".class");
        tempAgentJarOut.putNextEntry(entry);
        IOUtils.copy(
                DynamicInstrumentationAgent.class.getProtectionDomain().getCodeSource().getLocation().openStream(),
                tempAgentJarOut);
        tempAgentJarOut.closeEntry();
        tempAgentJarOut.close();
        return tempAgentJar;
    }

}
