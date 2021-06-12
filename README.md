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
