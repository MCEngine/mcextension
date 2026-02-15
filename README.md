# MCExtension API

MCExtension is a flexible, lightweight framework designed to allow Minecraft plugins to load external JARs as "Extensions." It features built-in dependency resolution for both Bukkit plugins and other extensions.

---

## 📦 Installation

To use MCExtension in your project, add the following to your `pom.xml`. Note that you must configure your GitHub credentials in your `settings.xml` to access the repository.

```xml
<dependency>
  <groupId>io.github.mcengine</groupId>
  <artifactId>mcextension</artifactId>
  <version>2026.0.3-4</version>
</dependency>
```

## 🛠️ Key Components

### IMCExtension (Interface)

The core interface defining the identity and lifecycle of an extension.

#### Methods

- `checkLicense(String url, String token)`: Optional method to verify the extension license. Automatically loads from `plugins/{main jar}/extensions/{ext name}/config.yml`. Returns `true` by default.

- `onLoad(JavaPlugin plugin, Executor executor)`: Triggered when the extension is loaded. Use this to register listeners or initialize logic.

- `onDisable(JavaPlugin plugin, Executor executor)`: Triggered when the extension is disabled. Use this to clean up resources.

- `checkUpdate(String url, String token)`: Optional method to implement custom update checks. Returns `false` by default.

## 🚀 Usage Examples

### 1. Creating an Extension

Implement the `IMCExtension` interface in your extension project.

```java
package io.github.example;

import io.github.mcengine.mcextension.api.IMCExtension;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.Executor;

public class MyExtension implements IMCExtension {

    @Override
    public void onLoad(JavaPlugin plugin, Executor executor) {
        plugin.getLogger().info("MyExtension has been loaded!");
    }

    @Override
    public void onDisable(JavaPlugin plugin, Executor executor) {
        plugin.getLogger().info("MyExtension is shutting down.");
    }

    @Override
    public boolean checkUpdate(String url, String token) {
        // Optional: Implement your update logic here
        // Return true if an update is found
        return false; 
    }

    @Override
    public boolean checkLicense(String url, String token) {
        // Optional: Implement license verification
        // If this method returns false, the extension will not load
        return token != null && !token.isEmpty();
    }
}
```

### ⚙️ Configuration

For extensions that require license verification, the `MCExtensionManager` automatically looks for a configuration file at: `plugins/{HostPlugin}/extensions/{ExtensionName}/config.yml`

#### Example `config.yml` structure

```yaml
license:
  url: "https://api.yourdomain.com/verify"
  token: "YOUR-LICENSE-KEY-HERE"
```

### 2. Managing Extensions

Use the `MCExtensionManager` in your main plugin to handle the loading process.

```java
public class MySpigotMCPlugin extends JavaPlugin {
    private MCExtensionManager extensionManager;

    @Override
    public void onEnable() {
        // Standard Spigot Async Executor
        Executor spigotExecutor = task -> 
            Bukkit.getScheduler().runTaskAsynchronously(this, task);

        this.extensionManager = new MCExtensionManager(this, spigotExecutor);
        this.extensionManager.loadAllExtensions();
    }

    @Override
    public void onDisable() {
        if (extensionManager != null) extensionManager.disableAllExtensions();
    }
}
```

```java
public class MyPaperMCPlugin extends JavaPlugin {
    private MCExtensionManager extensionManager;

    @Override
    public void onEnable() {
        // Modern Paper Async Executor
        Executor paperExecutor = task -> 
            Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> task.run());

        this.extensionManager = new MCExtensionManager(this, paperExecutor);
        this.extensionManager.loadAllExtensions();
    }

    @Override
    public void onDisable() {
        if (extensionManager != null) extensionManager.disableAllExtensions();
    }
}
```

```java
public class MyFoliaMCPlugin extends JavaPlugin {
    private MCExtensionManager extensionManager;

    @Override
    public void onEnable() {
        // Folia Region-Aware Async Executor
        Executor foliaExecutor = task -> 
            Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> task.run());

        this.extensionManager = new MCExtensionManager(this, foliaExecutor);
        this.extensionManager.loadAllExtensions();
    }

    @Override
    public void onDisable() {
        if (extensionManager != null) extensionManager.disableAllExtensions();
    }
}
```

## 📄 Extension Metadata (`extension.yml`)

Place this file in `src/main/resources` of your extension project to define its dependencies and main class.

```yaml
name: "MyExtension"
main: "io.github.mcengine.MyExtension"
version: "1.0.0"
extension:
  depend: [OtherExtID]     # optional: other extensions required
git:
  provider: github         # or gitlab
  owner: my-org            # repo owner/group
  repository: my-repo      # repo name
  # token resolution order:
  #   1) env USER_GITHUB_TOKEN / USER_GITLAB_TOKEN
  #   2) host plugin config git.<provider>.token (git.github.token / git.gitlab.token)
  #   3) host plugin config git.token
```
