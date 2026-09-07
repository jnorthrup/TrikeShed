package borg.trikeshed.graal.subvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A DEPARTMENT mounts as itself plus the module it extends.
 *
 * The property under test is the one that decides whether departmentalizing works at all:
 * a class from the department and a class from the spine must resolve through the SAME
 * loader, or a route can have components or an engine but never both.
 */
class GuestModuleDepartmentTest {

    private val department = "camel-mail"
    private val spine = "camel"

    private fun requireDepartment() {
        assertTrue(
            GuestModules.isInstalled(department),
            "guest module '$department' is not installed — run: ./gradlew -p utils/subvm installCamelMail",
        )
    }

    @Test
    fun theDepartmentDeclaresItsParentOnDisk() {
        requireDepartment()
        assertEquals(spine, GuestModules.manifest(department).parent, "the manifest must name the parent")
        assertEquals(null, GuestModules.manifest(spine).parent, "the spine extends nothing")
    }

    @Test
    fun theChainIsChildFirstAndTerminates() {
        requireDepartment()
        assertEquals(listOf(department, spine), GuestModules.chain(department))
        assertEquals(listOf(spine), GuestModules.chain(spine))
    }

    @Test
    fun theDepartmentShipsOnlyTheDeltaNotTheSpine() {
        requireDepartment()
        val own = GuestModules.jars(department).map { it.name }.toSet()
        val parentJars = GuestModules.jars(spine).map { it.name }.toSet()
        assertTrue(own.isNotEmpty(), "the department ships nothing at all")
        assertEquals(
            emptySet(), own intersect parentJars,
            "a jar shipped by both would put the same class on two loaders and stop being the same class",
        )
        // The composed classpath is what a mount actually sees: both halves, child first.
        val composed = GuestModules.composedClasspath(department).map { it.name }
        assertTrue(composed.containsAll(own), "composed classpath is missing the department's own jars")
        assertTrue(composed.containsAll(parentJars), "composed classpath is missing the spine")
    }

    @Test
    fun oneLoaderResolvesBothTheEngineAndTheDepartmentComponent() {
        requireDepartment()
        val loader = assertNotNull(GuestModules.loaderFor(department), "the department did not mount")
        // The spine, reached through the parent link.
        assertTrue(
            GuestModules.canResolve(loader, "org.apache.camel.impl.DefaultCamelContext"),
            "the department's loader cannot see the engine it extends",
        )
        // The department's own jars.
        assertTrue(
            GuestModules.canResolve(loader, "org.apache.camel.component.mail.MailComponent"),
            "the department's loader cannot see its own component",
        )
    }

    @Test
    fun theSpineCannotSeeTheDepartment() {
        requireDepartment()
        // Delegation is parent-first, so this is the documented limit rather than a defect:
        // a context built in the spine's loader will not find a department's components, which
        // is why VmSpec.module names the DEPARTMENT and the spine rides behind it.
        val spineLoader = assertNotNull(GuestModules.loaderFor(spine), "the spine did not mount")
        assertTrue(
            !GuestModules.canResolve(spineLoader, "org.apache.camel.component.mail.MailComponent"),
            "the spine can see the department — the isolation direction is inverted",
        )
    }
}
