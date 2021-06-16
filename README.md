# ForkTest - A Paper fork, using paperweight

This is an example project, showcasing how to setup a fork of paper (or well, any project), using paperweight.

The interesting part of this is in the build gradle
```
paperweight {
    serverProject.set(project(":ForkTest-Server"))

    usePaperUpstream(providers.gradleProperty("paperRef")) { // specified in gradle.properties
        withPaperPatcher {
            apiPatchDir.set(layout.projectDirectory.dir("patches/api"))
            apiOutputDir.set(layout.projectDirectory.dir("ForkTest-API"))

            serverPatchDir.set(layout.projectDirectory.dir("patches/server"))
            serverOutputDir.set(layout.projectDirectory.dir("ForkTest-Server"))
        }
    }
}

```

## Tasks

```
Paperweight tasks
-----------------
applyPatches
cleanCache - Delete the project setup cache and task outputs.
patchPaperApi
patchPaperServer
rebuildPaperApi
rebuildPaperServer
rebuildPatches
runDev - Spin up a non-shaded non-remapped test server
runShadow - Spin up a test server from the shadowJar archiveFile
```

## Branches

Each branch of this project represents an example:

 - [`main` is the standard example](https://github.com/PaperMC/paperweight-examples/tree/main)
 - [`submodules` shows how paperweight can be applied on a fork using the more traditional git submodule system](https://github.com/PaperMC/paperweight-examples/tree/submodules)
 - [`mojangapi` shows how a fork could patch arbitrary non-git directories (such as `Paper-MojangAPI`)](https://github.com/PaperMC/paperweight-examples/tree/mojangapi)
 - [`submodules-mojang` shows the same as `mojangapi`, but on the git submodules setup from `submodules`](https://github.com/PaperMC/paperweight-examples/tree/submodules-mojangapi)
