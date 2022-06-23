// tag::all[]
// tag::task[]
abstract class GreetingFileTask : DefaultTask() {

    @get:InputFiles
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun greet() {
        val file = destination.get().asFile
        if (source.get().asFile.exists()) {
            file.writeText("Hello ${source.get().asFile.readText()}")
        } else {
            file.writeText("Hello missing file!")
        }
    }
}
// end::task[]

// tag::config[]
val greetingFile = objects.fileProperty()

tasks.register<GreetingFileTask>("greet") {
    source.set(layout.projectDirectory.file("missing.txt"))
    destination.set(greetingFile)
}

tasks.register("sayGreeting") {
    dependsOn("greet")
    doLast {
        val file = greetingFile.get().asFile
        println("${file.readText()} (file: ${file.name})")
    }
}

greetingFile.set(layout.buildDirectory.file("hello.txt"))
// end::config[]
// end::all[]
