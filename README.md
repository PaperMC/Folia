# ForkTest - A Paper fork, using paperweight

This is an example project, showcasing how to setup a fork of Paper (or any other fork using paperweight), using paperweight.

The files of most interest are
- build.gradle.kts
- settings.gradle.kts
- gradle.properties

## Tasks

```
Paperweight tasks
-----------------
applyApiPatches
applyPatches
applyServerPatches
cleanCache - Delete the project setup cache and task outputs.
generateDevelopmentBundle
paperclipJar - Build a runnable paperclip jar
rebuildApiPatches
rebuildPatches
rebuildServerPatches
reobfJar - Re-obfuscate the built jar to obf mappings
runDev - Spin up a non-shaded non-remapped test server
runReobf - Spin up a test server from the reobfJar output jar
runShadow - Spin up a test server from the shadowJar archiveFile
```

## Branches

Each branch of this project represents an example:

 - [`main` is the standard example](https://github.com/PaperMC/paperweight-examples/tree/main)
 - [`submodules` shows how paperweight can be applied on a fork using the more traditional git submodule system](https://github.com/PaperMC/paperweight-examples/tree/submodules)
 - [`mojangapi` shows how a fork could patch arbitrary non-git directories (such as `Paper-MojangAPI`)](https://github.com/PaperMC/paperweight-examples/tree/mojangapi)
 - [`submodules-mojang` shows the same as `mojangapi`, but on the git submodules setup from `submodules`](https://github.com/PaperMC/paperweight-examples/tree/submodules-mojangapi)
