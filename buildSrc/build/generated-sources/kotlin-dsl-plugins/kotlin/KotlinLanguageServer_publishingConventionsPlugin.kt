/**
 * Precompiled [kotlin-language-server.publishing-conventions.gradle.kts][Kotlin_language_server_publishing_conventions_gradle] script plugin.
 *
 * @see Kotlin_language_server_publishing_conventions_gradle
 */
public
class KotlinLanguageServer_publishingConventionsPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Kotlin_language_server_publishing_conventions_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
