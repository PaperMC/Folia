# applyAllPatches 报错：git config exit 128

## 现象

执行 `.\gradlew.bat applyAllPatches` 时出现：

```
Execution failed for task ':applyPaperApiFilePatches'.
> io.papermc.paperweight.PaperweightException: Command finished with 128 exit code: 
  git -c commit.gpgsign=false -c core.safecrlf=false config commit.gpgSign false
```

或后续任务（如 `applyPaperMinecraftResourcePatches`）报同样的 **git exit 128**。

## 原因

paperweight 在**临时/工作目录**里执行 `git config commit.gpgSign false`。  
在部分 Windows 环境下（例如该目录是 worktree、路径或权限异常），这条命令会以 **exit code 128** 失败，导致 apply 流程中断。

**若项目所在磁盘是 exFAT：**  
exFAT 不支持 NTFS 的权限与部分文件语义，Git 在 exFAT 上写 `.git/config` 或做锁/重命名时更容易出问题，常表现为 exit 128。建议把项目放到 **NTFS** 分区再试。

## 已尝试的缓解方式

1. **全局关闭 GPG 签名**（建议仍做一次）  
   ```bat
   git config --global commit.gpgsign false
   ```  
   不能完全避免，因为插件会在**子目录**里再次执行 `config`。

2. **用 PATH 包装 git**  
   项目里提供了 `git.bat` 和 `applyPatches-with-git-fix.bat`，用“假”的 `git` 跳过有问题的 `config` 命令。  
   若 Gradle/paperweight 通过**完整路径**调用 `git.exe`，则不会用到该包装，仍会 128。

3. **跳过会失败的任务**  
   可先跳过 paper-api 的 apply，让后续步骤尽量跑下去（用于排查或临时用）：  
   ```bat
   .\gradlew.bat applyAllPatches -x applyPaperApiFilePatches -x applyPaperApiFeaturePatches
   ```  
   若再在 `applyPaperMinecraftResourcePatches` 等处报 128，说明所有“在子目录里跑 git config”的步骤都会受影响。

## 建议做法

1. **确认 Git 版本与安装路径**  
   - 在 PowerShell 里执行：`git --version`  
   - 确认 Git 来自官方安装（如 `C:\Program Files\Git\cmd\git.exe`），且无多版本冲突。

2. **在“干净”环境下重试**  
   - 关闭杀毒/实时扫描对项目与 `.gradle` 目录的监控后再跑一次 `applyAllPatches`。  
   - 或把项目放在路径较短、无空格、无特殊字符的目录（如 `I:\folia`）再试。  
   - **若当前是 exFAT 盘：把整个项目复制到 NTFS 分区（如 `C:\` 或另一块 NTFS 盘）再执行 apply，往往能消除 128。**

3. **向 PaperMC/paperweight 反馈**  
   - 在 [PaperMC/paperweight](https://github.com/PaperMC/paperweight/issues) 开 issue。  
   - 注明：Windows 版本、Git 版本、完整错误信息，以及是在 `applyPaperApiFilePatches` 还是 `applyPaperMinecraftResourcePatches`（或其它任务）上失败。

4. **在 WSL / Linux 下执行 apply**  
   - 在 WSL 或 Linux 上 clone 同一项目，在那边执行 `./gradlew applyAllPatches`。  
   - 若在 Linux 下成功，可把生成的 `paper-api`、`paper-server` 等目录拷回 Windows 再继续用 Gradle 构建（若你的工作流允许这样做）。

## 与本项目构建的关系

- **folia-api** 已能单独构建：`.\gradlew.bat :folia-api:build`。  
- **folia-server** 依赖 `applyAllPatches`（及后续 Minecraft 相关任务）生成并打补丁的 **paper-server** 等源码；若 apply 因 git 128 未完成，`.\gradlew.bat build` 会缺这些源码而编译失败。  
- 解决或绕过上述 git 128 后，再成功跑完 `applyAllPatches`，即可进行完整构建。

---

## 推荐：一键修复脚本（git 128 + Photographer 补丁）

项目提供 **`fix-and-apply-patches.ps1`**，可自动完成：

1. 将项目目录加入 PATH，使 `git.bat` 包装生效，避免 **git exit 128**。  
2. 临时禁用三个 Photographer 相关补丁（0005 api、0008 server paper、0009 server minecraft），让 `applyAllPatches` 先完整跑通一次。  
3. 从生成的 `paper-api` 和 `paper-server` 中读取正确 blob 哈希，写回补丁中的 `index` 行（解决 **could not build fake ancestor**）。  
4. 重新启用补丁并再次执行 `applyAllPatches`。

**用法（在项目根目录执行）：**

```powershell
.\fix-and-apply-patches.ps1
```

若仅需绕过 git 128、不修 Photographer 补丁，可只使用：

```bat
.\applyPatches-with-git-fix.bat
```

（内部会加 `--no-configuration-cache`，避免复用错误环境。）
