# react-native-secondary-screen

这是一个将 Android 原生 Presentation + ReactRootView 封装为可复用包的示例，允许在 Android 副屏（外接显示器/投影）上渲染 React Native 组件。

## 目录结构（简要）
- `android/src/main/java/com/reactnativesecondaryscreen/SecondaryScreenModule.java` - 原生模块实现
- `android/src/main/java/com/reactnativesecondaryscreen/SecondaryScreenPackage.java` - ReactPackage（手动注册或 autolinking 使用）
- `index.js` - JS 封装，导出 `show` / `dismiss`
- `index.d.ts` - TypeScript 类型声明
- `package.json` - 包信息

## 快速说明

- 使用 `DisplayManager` 查找副屏（DISPLAY_CATEGORY_PRESENTATION），基于 Display 创建 `Presentation`。
- 在 `Presentation` 中创建 `ReactRootView`，通过宿主应用的 `ReactInstanceManager` 启动指定组件（`startReactApplication`）。
- 暴露 JS API：`show(componentName)` 和 `dismiss()`，返回 Promise，便于错误处理与回退。

## 本地安装并自动链接（推荐，用于 RN 0.60+）

如果你希望把这个目录当作本地包安装并让 RN CLI autolink：

PowerShell 示例（在你的项目根目录执行）：

```powershell
npm install --save d:\project\demoApp\react-native-secondary-screen

yarn add file:d:\project\demoApp\react-native-secondary-screen
```

安装后，React Native CLI（autolinking）会读取 `react-native.config.js` 并把 Android 模块自动加入到构建配置中。随后运行 `npx react-native run-android` 或在 Android Studio 同步/构建，应该能自动链接并编译该模块。

> 注意：若你的工程有自定义的 Gradle 或特殊目录布局，autolink 可能失败，此时按下面“手动集成”步骤操作。

## 手动集成（如果 autolink 无效）

在 `android/settings.gradle` 中添加模块（示例路径根据你放置的位置调整）：

```gradle
include ':react-native-secondary-screen'
project(':react-native-secondary-screen').projectDir = new File(rootProject.projectDir, '../react-native-secondary-screen/android')
```

在 `android/app/build.gradle` 的 `dependencies` 中添加：

```gradle
implementation project(':react-native-secondary-screen')
```

在 `android/app/src/main/java/.../MainApplication.java` 中注册包（示例）：

```java
import com.reactnativesecondaryscreen.SecondaryScreenPackage;

// ...

@Override
protected List<ReactPackage> getPackages() {
  List<ReactPackage> packages = new PackageList(this).getPackages();
  // 如果自动链接失败，手动添加：
  packages.add(new SecondaryScreenPackage());
  return packages;
}
```

## 在 JS 中如何使用

示例：

```javascript
import SecondaryScreen from 'react-native-secondary-screen';

async function openSecondary() {
  try {
    const res = await SecondaryScreen.show('SecondaryScreen');
    console.log('副屏显示成功', res);
  } catch (e) {
    console.warn('无法显示副屏，降级处理', e);
    // 在主屏显示内容或提示用户
  }
}

async function closeSecondary() {
  try {
    await SecondaryScreen.dismiss();
  } catch (e) {
    console.warn('关闭副屏失败', e);
  }
}
```

说明：被渲染的组件必须在主 bundle 中通过 `AppRegistry.registerComponent(name, ...)` 注册，`name` 与 `show()` 中传入的 `componentName` 相同。

## 常见问题与排查

- 如果 `Native module "SecondaryScreen" is not linked`：确认安装后重启 Metro bundler，并在 Android Studio 执行 Gradle Clean / Rebuild；或手动在 `MainApplication` 中注册包。
- 如果显示失败并且 Promise 被 reject（`NO_DISPLAY`）：说明设备上没有可用副屏；在 JS 中捕获并降级处理。
- 如果 `ReactInstanceManager` 未就绪导致无法启动：确保主应用的 RN 初始化流程正常（`getReactNativeHost().getReactInstanceManager()` 可用），模块会尝试 `createReactContextInBackground()`，但某些自定义初始化需要开发者适配。

## 打包与发布建议

- 当前仓库为源码包装，适合本地开发和测试。若要在外部发布并被广泛复用，建议把 Android 部分配置为 Android Library（AAR）并发布到 Maven 仓库，同时在 `package.json` 中保留 JS 包入口。
- 可以扩展 `react-native.config.js`，或添加 Gradle 配置以支持多种项目结构。

## 扩展方向

- 支持选择具体 display（而非只使用第一个找到的），并暴露更多显示属性（位置、大小、安全区域等）。
- 增加事件回调，从副屏向主屏发送交互事件（通过 NativeEventEmitter / DeviceEventEmitter）。
- iOS 支持（使用 UIScreen + UIWindow，注意生命周期差异）。

## 许可证

MIT
